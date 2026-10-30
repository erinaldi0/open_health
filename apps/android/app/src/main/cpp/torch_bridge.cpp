// JNI bridge: run Oura's TorchScript models through the LibTorch lite interpreter on
// Android. A direct port of apps/ios/OuraApp/TorchBridge.mm — same entry points, same
// input order, same output element indices — so the two native clients produce
// bit-identical results from the same .ptl files. Sleep staging is NSSA
// (sleepstaging_2_6_0), which additionally registers the oura_ops::* custom ops that
// NssaOps.c implements. See docs/clients.md.
//
// The models bake their own preprocessing into the graph, so callers only supply raw
// input tensors (built in the Kotlin models/*.kt files, mirroring the Swift ones).
#include <jni.h>
#include <torch/csrc/jit/mobile/import.h>
#include <torch/csrc/jit/mobile/module.h>
#include <ATen/ATen.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <tuple>
#include <vector>

#include "NssaOps.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "openoura.torch", __VA_ARGS__)

// LibTorch lite is not safe to call concurrently. Hiking days (large tensors) plus four
// models used to race here and abort the process on iOS; the same applies here.
static std::mutex g_torch;

static torch::jit::mobile::Module &cachedModule(const std::string &path,
                                                std::unique_ptr<torch::jit::mobile::Module> &slot,
                                                std::string &slotPath) {
    if (!slot || slotPath != path) {
        slot = std::make_unique<torch::jit::mobile::Module>(
            torch::jit::_load_for_mobile(path, c10::nullopt));
        slotPath = path;
    }
    return *slot;
}

static at::Tensor blobLong(const int64_t *p, int64_t n) {
    if (n <= 0) return at::empty({0}, at::kLong);
    return at::from_blob((void *)p, {n}, at::kLong).clone();
}

static at::Tensor blobFloat2d(const float *p, int64_t rows, int64_t cols) {
    if (rows <= 0) return at::empty({0, cols}, at::kFloat);
    return at::from_blob((void *)p, {rows, cols}, at::kFloat).clone();
}

static at::Tensor mat(const float *p, int rows, int cols) {
    return rows > 0 ? blobFloat2d(p, rows, cols) : at::empty({0, cols}, at::kFloat);
}

// RAII views over Java primitive arrays. GetPrimitiveArrayCritical is deliberately NOT
// used: the model call is long and would pin the heap for its whole duration.
namespace {
struct FloatArr {
    JNIEnv *env; jfloatArray arr; jfloat *p; jsize n;
    FloatArr(JNIEnv *e, jfloatArray a) : env(e), arr(a), p(nullptr), n(0) {
        if (a) { p = e->GetFloatArrayElements(a, nullptr); n = e->GetArrayLength(a); }
    }
    ~FloatArr() { if (p) env->ReleaseFloatArrayElements(arr, p, JNI_ABORT); }
};
struct LongArr {
    JNIEnv *env; jlongArray arr; jlong *p; jsize n;
    LongArr(JNIEnv *e, jlongArray a) : env(e), arr(a), p(nullptr), n(0) {
        if (a) { p = e->GetLongArrayElements(a, nullptr); n = e->GetArrayLength(a); }
    }
    ~LongArr() { if (p) env->ReleaseLongArrayElements(arr, p, JNI_ABORT); }
};
struct Utf8 {
    JNIEnv *env; jstring s; const char *p;
    Utf8(JNIEnv *e, jstring str) : env(e), s(str), p(nullptr) {
        if (str) p = e->GetStringUTFChars(str, nullptr);
    }
    ~Utf8() { if (p) env->ReleaseStringUTFChars(s, p); }
    std::string str() const { return p ? std::string(p) : std::string(); }
};
} // namespace

// ─── oura_ops custom ops for the NSSA stager (sleepstaging_2_6_0) ──────────────
// Schemas match tools/nssa_ops.py; the .ptl calls them by name through the
// dispatcher. The implementations are the shared NssaOps.c, exactly as on iOS.
static std::mutex g_classifier_lock;
static std::vector<uint8_t> g_classifier_blob;
static std::string g_classifier_path;

void oura_nssa_set_classifier_path(const char *path) {
    std::lock_guard<std::mutex> lock(g_classifier_lock);
    if (path && g_classifier_path != path) {
        g_classifier_path = path;
        g_classifier_blob.clear();
    }
}

static const std::vector<uint8_t> &classifierBlob() {
    std::lock_guard<std::mutex> lock(g_classifier_lock);
    if (g_classifier_blob.empty() && !g_classifier_path.empty()) {
        FILE *f = fopen(g_classifier_path.c_str(), "rb");
        if (f) {
            fseek(f, 0, SEEK_END);
            long n = ftell(f);
            fseek(f, 0, SEEK_SET);
            if (n > 0) {
                g_classifier_blob.resize((size_t)n);
                if (fread(g_classifier_blob.data(), 1, (size_t)n, f) != (size_t)n) g_classifier_blob.clear();
            }
            fclose(f);
        }
    }
    return g_classifier_blob;
}

static std::tuple<at::Tensor, at::Tensor> op_create_windows(const at::Tensor &timestamps, double window_s) {
    auto ts = timestamps.reshape({-1}).to(at::kLong).contiguous();
    const int64_t n_ts = ts.numel();
    if (n_ts == 0) return {at::zeros({0, 1}, at::kLong), at::full({0, 4}, -1, at::kLong)};
    const int64_t n = std::max<int64_t>(0, nssa_create_windows(ts.data_ptr<int64_t>(), n_ts, window_s, nullptr, nullptr));
    auto wts = at::zeros({n}, at::kLong);
    auto rows = at::full({n, 4}, -1, at::kLong);
    if (n > 0) nssa_create_windows(ts.data_ptr<int64_t>(), n_ts, window_s, wts.data_ptr<int64_t>(), rows.data_ptr<int64_t>());
    return {wts.reshape({-1, 1}), rows};
}

static at::Tensor op_find_indices(const at::Tensor &x, const at::Tensor &xnew) {
    auto xs = x.reshape({-1}).to(at::kLong).contiguous();
    auto vs = xnew.reshape({-1}).to(at::kLong).contiguous();
    auto out = at::zeros({vs.numel()}, at::kLong);
    if (nssa_find_indices(xs.data_ptr<int64_t>(), xs.numel(), vs.data_ptr<int64_t>(), vs.numel(),
                          out.data_ptr<int64_t>()) != 0)
        throw std::runtime_error("oura_find_indices: native code would read past x");
    return out.reshape(xnew.sizes());
}

static at::Tensor op_biquad_cascade(const at::Tensor &sos, const at::Tensor &x) {
    auto s = sos.to(at::kFloat).contiguous();
    auto v = x.to(at::kFloat).reshape({-1}).contiguous();
    auto out = at::empty_like(v);
    if (s.size(0) > 64) throw std::runtime_error("oura_biquad_cascade: too many sections");
    nssa_biquad_cascade(s.data_ptr<float>(), s.size(0), v.data_ptr<float>(), v.numel(), out.data_ptr<float>());
    return out.reshape(x.sizes()).to(x.scalar_type());
}

static at::Tensor op_find_peaks(const at::Tensor &x) {
    auto v = x.to(at::kFloat).reshape({-1}).contiguous();
    auto out = at::zeros({std::max<int64_t>(v.numel() / 2, 1)}, at::kLong);
    const int64_t m = nssa_find_peaks(v.data_ptr<float>(), v.numel(), out.data_ptr<int64_t>());
    return out.slice(0, 0, m).clone();
}

static at::Tensor op_sleep_classifier(const at::Tensor &features) {
    auto f = features.to(at::kFloat).contiguous();
    if (f.dim() != 2 || f.size(1) != 182) throw std::runtime_error("oura_sleep_classifier expects [N,182]");
    const auto &blob = classifierBlob();
    if (blob.empty()) throw std::runtime_error("oura_sleep_classifier: classifier blob missing");
    auto out = at::empty({f.size(0), 4}, at::kFloat);
    if (f.size(0) > 0 &&
        nssa_classifier_predict(blob.data(), (int64_t)blob.size(), f.data_ptr<float>(), f.size(0), f.size(1),
                                out.data_ptr<float>()) != 0)
        throw std::runtime_error("oura_sleep_classifier: bad classifier blob");
    return out;
}

TORCH_LIBRARY(oura_ops, m) {
    m.def("oura_create_windows(Tensor timestamps, float window_s) -> (Tensor, Tensor)", op_create_windows);
    m.def("oura_find_indices(Tensor x, Tensor xnew) -> Tensor", op_find_indices);
    m.def("oura_biquad_cascade(Tensor sos, Tensor x) -> Tensor", op_biquad_cascade);
    m.def("oura_find_peaks(Tensor x) -> Tensor", op_find_peaks);
    m.def("oura_sleep_classifier(Tensor features) -> Tensor", op_sleep_classifier);
}

// The lite build ships no Conjugate/Negative fallbacks, yet NSSA's graph routes a
// few real-valued ops (numpy_T + mul in its smoothing) through those keys. Real
// tensors never carry the conj/neg bits, so passing through is exact.
TORCH_LIBRARY_IMPL(_, Conjugate, m) { m.fallback(torch::CppFunction::makeFallthrough()); }
TORCH_LIBRARY_IMPL(_, Negative, m) { m.fallback(torch::CppFunction::makeFallthrough()); }

extern "C" {

/**
 * Oura's NSSA sleep stager (sleepstaging_2_6_0) over one ring bedtime — a port of
 * TorchBridge.mm's oura_nssa, matching tools/nssa_stager.py. Inputs are built by the
 * Kotlin NssaInputs (twin of tools/nssa_inputs.py): acmVal is row-major n×6
 * [madIqr, madMax, madTmean, faIqr, faMax, faMean], ibiVal is n×3
 * [ibi, amplitude, validity] (ecore-corrected), tempVal is n, demographics is
 * [age, bmi, sex]. Returns the per-30s stage codes (1=DEEP 2=LIGHT 3=REM 4=WAKE),
 * or null on failure, and writes each epoch's start stamp into `out_timestamps_ms`
 * (which bounds the epoch count).
 */
JNIEXPORT jintArray JNICALL
Java_md_thomas_openoura_models_TorchBridge_nssa(
        JNIEnv *env, jobject, jstring model_path,
        jlongArray acm_ts, jfloatArray acm_val, jint n_acm,
        jlongArray ibi_ts, jfloatArray ibi_val, jint n_ibi,
        jlongArray temp_ts, jfloatArray temp_val, jint n_temp,
        jfloatArray demographics,
        jlong bedtime_start_ms, jlong bedtime_end_ms,
        jlongArray out_timestamps_ms, jint max_out) {
    Utf8 path(env, model_path);
    LongArr acmTs(env, acm_ts); FloatArr acmVal(env, acm_val);
    LongArr ibiTs(env, ibi_ts); FloatArr ibiVal(env, ibi_val);
    LongArr tempTs(env, temp_ts); FloatArr tempVal(env, temp_val);
    FloatArr demoArr(env, demographics);
    if (!path.p || !demoArr.p || max_out <= 0 || n_acm <= 0 || n_ibi <= 0 || n_temp <= 0) return nullptr;
    if ((int)acmTs.n < n_acm || (int)acmVal.n < 6 * n_acm || (int)ibiTs.n < n_ibi ||
        (int)ibiVal.n < 3 * n_ibi || (int)tempTs.n < n_temp || (int)tempVal.n < n_temp) return nullptr;
    if ((int)demoArr.n < 3 || env->GetArrayLength(out_timestamps_ms) < max_out) return nullptr;

    try {
        std::lock_guard<std::mutex> lock(g_torch);
        static std::unique_ptr<torch::jit::mobile::Module> cached;
        static std::string cachedPath;
        auto &m = cachedModule(path.str(), cached, cachedPath);
        int64_t bt[2] = {(int64_t)bedtime_start_ms, (int64_t)bedtime_end_ms};
        std::vector<c10::IValue> inputs{
            blobFloat2d(acmVal.p, n_acm, 6), blobLong(acmTs.p, n_acm),
            blobFloat2d(ibiVal.p, n_ibi, 3), blobLong(ibiTs.p, n_ibi),
            at::from_blob((void *)tempVal.p, {n_temp}, at::kFloat).clone(), blobLong(tempTs.p, n_temp),
            at::from_blob((void *)demoArr.p, {3}, at::kFloat).clone(), blobLong(bt, 2)};
        auto out = m.forward(inputs).toTuple();
        auto stages = out->elements()[1].toTensor();               // [epochs, 1 + 4 probs]
        if (stages.dim() < 2 || stages.size(1) < 1) {
            LOGE("nssa: unexpected stage shape");
            return nullptr;
        }
        auto col0 = stages.select(1, 0).to(at::kDouble).round().to(at::kInt).contiguous();
        auto timestamps = out->elements()[0].toTensor().to(at::kLong).reshape({-1}).contiguous();
        if (timestamps.numel() != col0.numel() || (int64_t)col0.numel() > max_out) {
            LOGE("nssa: mismatched timestamps or output exceeds buffer");
            return nullptr;
        }
        const int n = (int)col0.numel();
        env->SetLongArrayRegion(out_timestamps_ms, 0, n, (const jlong *)timestamps.data_ptr<int64_t>());
        jintArray result = env->NewIntArray(n);
        if (!result) return nullptr;
        env->SetIntArrayRegion(result, 0, n, (const jint *)col0.data_ptr<int>());
        return result;
    } catch (const std::exception &e) {
        LOGE("nssa: %s", e.what());
        return nullptr;
    }
}

/** Point `oura_ops::oura_sleep_classifier` at the bundled forest (see
 *  tools/extract_nssa_classifier.py). Must be called before the first nssa() run. */
JNIEXPORT void JNICALL
Java_md_thomas_openoura_models_TorchBridge_nssaSetClassifierPath(JNIEnv *env, jobject, jstring path) {
    Utf8 p(env, path);
    oura_nssa_set_classifier_path(p.str().c_str());
}

/**
 * Cardiovascular age (cva_2_1_0): forward(ppg [n_segs×1500] f32, demo [1×5] f32) →
 * (daily_cva, quality, raw_quality, daily_pwv, ppg_segment_metrics).
 * Returns {vascular_age, pwv}, or null on failure.
 */
JNIEXPORT jdoubleArray JNICALL
Java_md_thomas_openoura_models_TorchBridge_cva(
        JNIEnv *env, jobject, jstring model_path, jfloatArray ppg, jint n_segs, jfloatArray demo) {
    Utf8 path(env, model_path);
    FloatArr ppgA(env, ppg); FloatArr demoA(env, demo);
    if (!path.p || !ppgA.p || !demoA.p || n_segs <= 0) return nullptr;

    std::lock_guard<std::mutex> lock(g_torch);
    try {
        static std::unique_ptr<torch::jit::mobile::Module> cached;
        static std::string cachedPath;
        auto &m = cachedModule(path.str(), cached, cachedPath);
        auto ppg_t = blobFloat2d(ppgA.p, n_segs, 1500);
        auto demo_t = blobFloat2d(demoA.p, 1, 5);
        auto out = m.forward({ppg_t, demo_t}).toTuple();
        double values[2] = {
            out->elements()[0].toTensor().item<double>(),
            out->elements()[3].toTensor().item<double>(),
        };
        jdoubleArray result = env->NewDoubleArray(2);
        if (!result) return nullptr;
        env->SetDoubleArrayRegion(result, 0, 2, values);
        return result;
    } catch (const std::exception &e) {
        LOGE("cva: %s", e.what());
        return nullptr;
    }
}

/**
 * Automatic activity detection. Mirrors run_activity_model.py's
 * forward(context[4], user[14], met[n×2], step[n×12], motion[n×9], temp[n×2], hr[n×2],
 *         None, None, threshold, min_duration, 0.0).
 * Returns workouts row-major, 9 floats per row:
 * [start_min, end_min, is_workout, id1, p1, id2, p2, id3, p3].
 */
JNIEXPORT jfloatArray JNICALL
Java_md_thomas_openoura_models_TorchBridge_activity(
        JNIEnv *env, jobject, jstring model_path,
        jfloatArray context, jfloatArray user,
        jfloatArray met, jint n_met, jfloatArray step, jint n_step,
        jfloatArray motion, jint n_motion, jfloatArray temp, jint n_temp,
        jfloatArray hr, jint n_hr, jfloat threshold, jfloat min_duration) {
    Utf8 path(env, model_path);
    FloatArr ctxA(env, context), userA(env, user), metA(env, met), stepA(env, step);
    FloatArr motionA(env, motion), tempA(env, temp), hrA(env, hr);
    if (!path.p || !ctxA.p || !userA.p) return nullptr;

    std::lock_guard<std::mutex> lock(g_torch);
    try {
        // Called once per retained local day. Loading the 15 MB module for every day
        // dominated sync time on iOS, so retain it for the process lifetime here too.
        static std::unique_ptr<torch::jit::mobile::Module> cached;
        static std::string cachedPath;
        auto &m = cachedModule(path.str(), cached, cachedPath);

        auto context_t = at::from_blob((void *)ctxA.p, {4}, at::kFloat).clone();
        auto user_t = at::from_blob((void *)userA.p, {14}, at::kFloat).clone();
        auto met_t = mat(metA.p, n_met, 2);
        auto step_t = mat(stepA.p, n_step, 12);
        auto motion_t = mat(motionA.p, n_motion, 9);
        auto temp_t = mat(tempA.p, n_temp, 2);
        auto hr_t = mat(hrA.p, n_hr, 2);

        auto thr = at::full({}, threshold, at::kFloat);   // 0-dim scalars
        auto mind = at::full({}, min_duration, at::kFloat);
        auto zero = at::full({}, 0.f, at::kFloat);
        std::vector<c10::IValue> inputs{context_t, user_t, met_t, step_t, motion_t, temp_t, hr_t,
                                        c10::IValue(), c10::IValue(), thr, mind, zero};
        auto out = m.forward(inputs).toTuple();
        auto workouts = out->elements()[0].toTensor().to(at::kFloat).contiguous();
        if (!workouts.defined() || workouts.numel() == 0) return env->NewFloatArray(0);
        if (workouts.dim() != 2 || workouts.size(1) != 9) {
            LOGE("activity: unexpected workouts shape dim=%d", (int)workouts.dim());
            return nullptr;
        }
        jsize n = (jsize)(workouts.size(0) * 9);
        jfloatArray result = env->NewFloatArray(n);
        if (!result) return nullptr;
        env->SetFloatArrayRegion(result, 0, n, workouts.data_ptr<float>());
        return result;
    } catch (const std::exception &e) {
        LOGE("activity: %s", e.what());
        return nullptr;
    }
}

/**
 * Decode Ring 5 real-step packets with Oura's steps_motion_decoder. `raw` is N×27
 * quantized input, timestamps are Unix milliseconds. Returns a float array laid out as
 * [ts0_lo..] — actually returns features only; timestamps come back via `out_timestamps`.
 */
JNIEXPORT jfloatArray JNICALL
Java_md_thomas_openoura_models_TorchBridge_stepMotion(
        JNIEnv *env, jobject, jstring model_path,
        jlongArray timestamps_ms, jfloatArray raw, jint n_raw, jlongArray out_timestamps_ms) {
    Utf8 path(env, model_path);
    LongArr tsA(env, timestamps_ms); FloatArr rawA(env, raw);
    if (!path.p || !tsA.p || !rawA.p || n_raw <= 0) return nullptr;

    std::lock_guard<std::mutex> lock(g_torch);
    try {
        static std::unique_ptr<torch::jit::mobile::Module> cached;
        static std::string cachedPath;
        auto &m = cachedModule(path.str(), cached, cachedPath);
        auto timestamps = blobLong((const int64_t *)tsA.p, n_raw);
        auto data = blobFloat2d(rawA.p, n_raw, 27);
        auto result = m.forward({timestamps, data}).toTuple();
        auto out_ts = result->elements()[0].toTensor().reshape({-1}).to(at::kLong).contiguous();
        auto out_data = result->elements()[1].toTensor().to(at::kFloat).contiguous();
        if (!out_data.defined() || out_data.numel() == 0) return env->NewFloatArray(0);
        if (out_data.dim() != 2 || out_data.size(1) != 11) {
            LOGE("stepMotion: unexpected feature shape dim=%d", (int)out_data.dim());
            return nullptr;
        }
        jsize rows = (jsize)std::min<int64_t>(out_data.size(0), out_ts.numel());
        jsize cap = env->GetArrayLength(out_timestamps_ms);
        rows = std::min(rows, cap);
        env->SetLongArrayRegion(out_timestamps_ms, 0, rows, (const jlong *)out_ts.data_ptr<int64_t>());
        jfloatArray features = env->NewFloatArray(rows * 11);
        if (!features) return nullptr;
        env->SetFloatArrayRegion(features, 0, rows * 11, out_data.data_ptr<float>());
        return features;
    } catch (const std::exception &e) {
        LOGE("stepMotion: %s", e.what());
        return nullptr;
    }
}

/**
 * Illness detection ("Symptom Radar"). `series` is row-major 7×30 (index 0 = today,
 * NaN = missing): average_breath, average_heart_rate, lowest_heart_rate, average_hrv,
 * temperature_deviation, sedentary_time(s), resting_time(s). `scalars` is 12 values:
 * age, BMI, sex(-1/+1/0), day_of_week, then the 8 long-term baselines.
 *
 * Returns 18 floats: [score, decision, then 4 biomarkers × (is_out, value, lower, upper)].
 * The menstrual-cycle inputs are fed as NaN internally (male/no-cycle path → group 0).
 */
JNIEXPORT jfloatArray JNICALL
Java_md_thomas_openoura_models_TorchBridge_illness(
        JNIEnv *env, jobject, jstring model_path, jfloatArray series, jfloatArray scalars) {
    Utf8 path(env, model_path);
    FloatArr seriesA(env, series), scalarsA(env, scalars);
    if (!path.p || !seriesA.p || !scalarsA.p) return nullptr;
    if (seriesA.n < 7 * 30 || scalarsA.n < 12) {
        LOGE("illness: short inputs (series=%d scalars=%d)", (int)seriesA.n, (int)scalarsA.n);
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(g_torch);
    try {
        static std::unique_ptr<torch::jit::mobile::Module> cached;
        static std::string cachedPath;
        auto &m = cachedModule(path.str(), cached, cachedPath);

        const float nan = std::numeric_limits<float>::quiet_NaN();
        const float *series_p = seriesA.p;
        const float *sc_p = scalarsA.p;
        auto colf = [&](int k) { return blobFloat2d(series_p + k * 30, 30, 1); };
        auto nanCol = at::full({30, 1}, nan, at::kFloat);   // cycle_phase / reason (male)
        auto sc = [&](float v) { return at::full({1, 1}, v, at::kFloat); };

        std::vector<c10::IValue> inputs{
            colf(0), colf(1), colf(2), colf(3), colf(4), colf(5), colf(6),  // 1-7 series
            nanCol, nanCol,                                                 // 8-9 cycle/reason
            sc(nan), sc(nan),                                               // 10-11 period/ovulation
            sc(sc_p[0]), sc(sc_p[1]), sc(sc_p[2]), sc(sc_p[3]),             // 12-15 age,bmi,sex,dow
            sc(sc_p[4]), sc(sc_p[5]), sc(sc_p[6]), sc(sc_p[7]),             // 16-19 rhr/hrv avg+dev
            sc(sc_p[8]), sc(sc_p[9]), sc(sc_p[10]), sc(sc_p[11])};          // 20-23 temp/sed avg+dev

        auto out = m.forward(inputs).toTuple();
        auto &el = out->elements();
        float result[18];
        result[0] = (float)el[0].toTensor().item<double>();
        result[1] = (float)std::lround(el[1].toTensor().item<double>());
        // 4 shown biomarkers: output indices 2(breath) 4(lowest_hr) 5(hrv) 6(temp)
        const int idx[4] = {2, 4, 5, 6};
        for (int b = 0; b < 4; b++) {
            auto v = el[idx[b]].toTensor().to(at::kFloat).contiguous();
            if (v.numel() < 4) {
                LOGE("illness: biomarker %d too short (%d)", b, (int)v.numel());
                return nullptr;
            }
            const float *vp = v.data_ptr<float>();
            for (int j = 0; j < 4; j++) result[2 + b * 4 + j] = vp[j]; // [is_out,value,min,max]
        }
        jfloatArray arr = env->NewFloatArray(18);
        if (!arr) return nullptr;
        env->SetFloatArrayRegion(arr, 0, 18, result);
        return arr;
    } catch (const std::exception &e) {
        LOGE("illness: %s", e.what());
        return nullptr;
    }
}

} // extern "C"
