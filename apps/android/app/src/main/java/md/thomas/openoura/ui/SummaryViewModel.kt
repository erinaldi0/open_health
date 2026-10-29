package md.thomas.openoura.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import md.thomas.openoura.core.Core
import md.thomas.openoura.data.DayAnalysisKind
import md.thomas.openoura.data.Summary
import md.thomas.openoura.diag.Diagnostics.log
import md.thomas.openoura.health.HealthExport
import md.thomas.openoura.models.ModelPipeline
import md.thomas.openoura.store.SummaryCache

/**
 * The load pipeline, ported from RootView.load in apps/ios/OuraApp/OuraApp.swift:
 * show the cached summary instantly → compute the model-free summary off the main
 * thread → publish → run the on-device models → publish again.
 *
 * A generation counter guards against an older pass overwriting a newer one when the
 * user syncs while a model run is still going.
 */
class SummaryViewModel(app: Application) : AndroidViewModel(app) {

    var summary by mutableStateOf<Summary?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    /** A per-day refresh is running (the day report's rerun button). */
    var refreshingDay by mutableStateOf(false)
        private set

    /** e.g. "staging sleep · night 3/12" — the ModelProgress pill on iOS. */
    var modelProgress by mutableStateOf<String?>(null)
        private set

    private var generation = 0
    private var job: Job? = null

    init {
        summary = SummaryCache.load(app)
        reload()
    }

    fun reload() {
        val app = getApplication<Application>()
        generation += 1
        val mine = generation
        job?.cancel()
        job = viewModelScope.launch {
            loading = true
            try {
                val base = withContext(Dispatchers.IO) { Core.base(app) }
                if (mine != generation) return@launch
                if (base.error != null) {
                    log("core", "summary error: ${base.error}")
                    // Keep whatever is already on screen: an empty DB before the first
                    // sync is a normal state, not a reason to blank the UI.
                    if (summary == null) summary = base
                } else {
                    summary = base
                    SummaryCache.save(app, base)
                    HealthExport.push(app, base)
                }

                // The slow part: the on-device torch models. Absent in the `lite`
                // flavor, where ModelPipeline is a no-op and this returns immediately.
                val previous = summary
                val withModels = withContext(Dispatchers.Default) {
                    ModelPipeline.run(
                        context = app,
                        base = base,
                        previous = previous,
                        progress = { step -> viewModelScope.launch { modelProgress = step } },
                    )
                }
                if (mine != generation) return@launch
                if (withModels != null) {
                    summary = withModels
                    SummaryCache.save(app, withModels)
                    // Push again after the models land: the second pass is the one that
                    // carries sleep stages and detected sessions.
                    HealthExport.push(app, withModels)
                }
            } finally {
                if (mine == generation) {
                    loading = false
                    modelProgress = null
                }
            }
        }
    }

    /**
     * Rerun one day's report without touching the other models or days. Port of
     * `refreshDayAnalysis` in apps/ios/OuraApp/OuraApp.swift: publishes the updated
     * summary on success (guarded against a concurrent reload), returns a user-facing
     * error, or null on success.
     */
    suspend fun refreshDay(day: String, kind: DayAnalysisKind): String? {
        val app = getApplication<Application>()
        if (loading || refreshingDay) return "Wait for sync and analysis to finish, then try again."
        val mine = generation
        val previous = summary ?: return "Wait for sync and analysis to finish, then try again."
        refreshingDay = true
        val (updated, error) = withContext(Dispatchers.Default) {
            ModelPipeline.refreshDay(app, previous, day, kind) { step ->
                viewModelScope.launch { modelProgress = step }
            }
        }
        refreshingDay = false
        modelProgress = null
        if (error == null && mine == generation) {
            summary = updated
            SummaryCache.save(app, updated)
            HealthExport.push(app, updated)
        }
        return error
    }
}
