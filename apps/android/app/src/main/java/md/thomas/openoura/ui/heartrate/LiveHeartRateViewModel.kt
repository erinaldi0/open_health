package md.thomas.openoura.ui.heartrate

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import md.thomas.openoura.ble.SyncEngine
import md.thomas.openoura.diag.Diagnostics.log

data class HeartRateUiState(
    val isConnecting: Boolean = false,
    val isMonitoring: Boolean = false,
    val bpm: Int? = null,
    /** Last raw beat interval. */
    val ibiMs: Int? = null,
    /** Beats behind [bpm], which is smoothed over ~10 s (see [HeartRateSmoother]). */
    val beatsInWindow: Int = 0,
    /** Too few good beats to trust [bpm] (see [SignalQuality]); show a warning instead. */
    val weakSignal: Boolean = false,
    val statusMessage: String = "",
    /** Reference readings from another device, newest last. */
    val references: List<ReferenceReading> = emptyList()
)

/**
 * A BPM read off another device (e.g. a watch) at [atMs], next to what this app showed at
 * that moment. Logged so a raw-frame capture can be checked against ground truth.
 */
data class ReferenceReading(val atMs: Long, val referenceBpm: Int, val appBpm: Int?)

class LiveHeartRateViewModel(application: Application) : AndroidViewModel(application) {

    private val syncEngine = SyncEngine(application)
    private var hrJob: Job? = null
    private val smoother = HeartRateSmoother()
    private val quality = SignalQuality()
    // Beats arrive on a Rust (tokio) thread; the weak-signal poll runs on main.
    private val beatLock = Any()

    private val _uiState = MutableStateFlow(HeartRateUiState())
    val uiState: StateFlow<HeartRateUiState> = _uiState.asStateFlow()

    fun startLiveHeartRate() {
        if (_uiState.value.isMonitoring || _uiState.value.isConnecting) return

        synchronized(beatLock) {
            smoother.reset()
            quality.reset()
        }
        _uiState.value = HeartRateUiState(isConnecting = true, statusMessage = "Initializing…")

        hrJob = viewModelScope.launch {
            // Coverage is wall-clock based: re-check every second so a stream that goes
            // quiet turns "weak" even though no beat callback fires.
            val poll = launch {
                while (true) {
                    delay(1_000)
                    if (_uiState.value.isMonitoring) refreshSignal(ibiMs = null)
                }
            }
            syncEngine.liveHeartRate(
                onHeartRate = { _, ibiMs -> refreshSignal(ibiMs) },
                onStatus = { status ->
                    _uiState.value = _uiState.value.copy(statusMessage = status)
                }
            )
            poll.cancel()
            // Stream ended on its own (time limit or error). Keep an error/setup message
            // visible; only a stream that was actually running reports "stopped".
            val wasMonitoring = _uiState.value.isMonitoring
            _uiState.value = _uiState.value.copy(
                isConnecting = false,
                isMonitoring = false,
                statusMessage = if (wasMonitoring) "Live heart rate stopped" else _uiState.value.statusMessage
            )
        }
    }

    /**
     * Fold in a new beat (if [ibiMs] is non-null) and republish bpm + signal state. Per-beat
     * bpm is too jumpy to read, so the screen gets the 10 s smoothed value.
     */
    private fun refreshSignal(ibiMs: Int?) {
        val now = SystemClock.elapsedRealtime()
        var coverage: Double? = null
        val (bpm, beats, weak) = synchronized(beatLock) {
            if (ibiMs != null && smoother.add(ibiMs)) quality.addBeat(now, ibiMs)
            coverage = quality.coverage(now)
            Triple(smoother.bpm, smoother.beatsInWindow, quality.isWeak(now))
        }
        if (weak != _uiState.value.weakSignal) {
            log("live-hr", if (weak) "weak signal — coverage $coverage" else "signal recovered — coverage $coverage")
        }
        _uiState.update {
            it.copy(
                isConnecting = false,
                isMonitoring = true,
                bpm = bpm,
                ibiMs = ibiMs ?: it.ibiMs,
                beatsInWindow = beats,
                weakSignal = weak,
            )
        }
    }

    /**
     * Record [referenceBpm] as read at [atMs] (when the user started typing it, so typing
     * time doesn't skew it against the ring data). Goes to the diagnostics log — the same
     * logcat as the raw BLE frames — and to the on-screen list.
     */
    fun logReference(referenceBpm: Int, atMs: Long) {
        val state = _uiState.value
        val reading = ReferenceReading(atMs, referenceBpm, state.bpm.takeUnless { state.weakSignal })
        log(
            "live-hr-ref",
            "at=$atMs reference=$referenceBpm app=${state.bpm ?: "-"} " +
                "beats=${state.beatsInWindow} lastIbi=${state.ibiMs ?: "-"} weak=${state.weakSignal}"
        )
        _uiState.value = state.copy(references = state.references + reading)
    }

    fun stopLiveHeartRate() {
        hrJob?.cancel()
        hrJob = null
        _uiState.value = HeartRateUiState(statusMessage = "Stopped", references = _uiState.value.references)
    }

    override fun onCleared() {
        super.onCleared()
        stopLiveHeartRate()
    }
}