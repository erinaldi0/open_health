package md.thomas.openoura.ble

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import androidx.work.WorkInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.oura_core.databaseIntegrity
import uniffi.oura_core.exportDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import md.thomas.openoura.diag.Diagnostics.log
import md.thomas.openoura.store.DB
import md.thomas.openoura.store.Prefs
import md.thomas.openoura.store.RingKeyStore

/**
 * UI-facing controller over the WorkManager-driven sync. The BLE work itself runs in
 * [RingSyncWorker] / [SyncEngine]; this ViewModel only enqueues it and mirrors the observed
 * [WorkInfo] into Compose state for the sync screen and the home header. Mirrors the surface
 * RingSync.swift exposes on iOS, minus the sync loop (which moved to the worker so it can run
 * in the background).
 */
class RingSync(app: android.app.Application) : AndroidViewModel(app) {

    companion object {
        /** cacheDir subfolder shared through the FileProvider (res/xml/file_paths.xml). */
        const val EXPORT_DIR = "exports"
    }

    var status by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var lastSummary by mutableStateOf<SyncSummary?>(null)
        private set
    var lastSuccessfulSyncAtMs by mutableStateOf<Long?>(null)
        private set

    private val context: Context get() = getApplication()

    init {
        lastSuccessfulSyncAtMs = Prefs.of(app).getLong(Prefs.LAST_SUCCESSFUL_SYNC_AT, 0L).takeIf { it > 0 }
        // Reflect every sync — scheduled, app-start, or manual — into Compose state.
        viewModelScope.launch {
            SyncScheduler.observe(context).collect(::onWorkInfos)
        }
    }

    private fun onWorkInfos(infos: List<WorkInfo>) {
        val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        busy = running != null

        val succeeded = infos.filter { it.state == WorkInfo.State.SUCCEEDED }
        if (running != null) {
            running.progress.getString(RingSyncWorker.KEY_STATUS)
                ?.takeIf { it.isNotEmpty() }
                ?.let { status = it }
        } else {
            // A finished run leaves its terminal message in output Data — prefer a success,
            // otherwise any finished run's message (the failure explanation).
            val terminal = succeeded.ifEmpty { infos.filter { it.state.isFinished } }
            terminal.firstNotNullOfOrNull {
                it.outputData.getString(RingSyncWorker.KEY_STATUS)?.takeIf { s -> s.isNotEmpty() }
            }?.let { status = it }
        }

        // A newly succeeded run publishes its summary; refresh the last-sync timestamp from
        // the same prefs the worker wrote.
        val summary = succeeded.firstNotNullOfOrNull { SyncSummary.fromData(it.outputData) }
        if (summary != null && summary != lastSummary) {
            lastSummary = summary
            lastSuccessfulSyncAtMs =
                Prefs.of(context).getLong(Prefs.LAST_SUCCESSFUL_SYNC_AT, 0L).takeIf { it > 0 }
        }
    }

    fun wasRecentlySynced(nowMs: Long = System.currentTimeMillis()): Boolean =
        lastSuccessfulSyncAtMs?.let { nowMs - it < SyncEngine.AUTOMATIC_SYNC_COOLDOWN_MS } ?: false

    fun hasStoredKey(): Boolean = RingKeyStore.load(context) != null

    /** Manual "Connect & Sync". The key is read from the Keystore inside the worker. */
    fun sync() = SyncScheduler.syncNow(context, RingSyncWorker.SOURCE_MANUAL)

    /** Stop the running sync; the next one resumes from the checkpointed cursor. */
    fun pause() {
        if (!busy) return
        viewModelScope.launch(Dispatchers.IO) { SyncScheduler.pause(context) }
        status = "Sync paused. Sync again to resume from where it stopped."
        log("sync", "paused by the user")
    }

    /** `PRAGMA integrity_check`-style verdict on the database the app reads. */
    suspend fun checkDatabase(): String = withContext(Dispatchers.IO) {
        val verdict = try {
            "Database check: ${databaseIntegrity(DB.readPath(context))}"
        } catch (e: Throwable) {
            "Database check failed: ${e.message ?: e}"
        }
        log("db", verdict)
        verdict
    }

    /**
     * A self-contained copy of the ring database for sharing (raw ring records only; the auth
     * key lives in the Keystore, not in the DB). It's a one-step SQLite backup on its own
     * connection, which in WAL mode copies a consistent snapshot, so it can run alongside a
     * sync. On a computer it's a normal `oura --db <file> …` input. Mirrors iOS
     * `RingSync.exportRawDatabase`.
     */
    suspend fun exportRawDatabase(): Result<File> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only ever keep the latest export
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val out = File(dir, "oura-ring-$stamp-${UUID.randomUUID().toString().take(8)}.db")
        try {
            exportDatabase(DB.readPath(context), out.absolutePath)
            log("db", "exported ${out.name} (${out.length()} bytes)")
            Result.success(out)
        } catch (e: Throwable) {
            out.delete()
            log("db", "export failed: ${e.message ?: e}")
            Result.failure(e)
        }
    }

    /**
     * Replace the local database with a backup. The chosen file is copied to a staging file
     * and integrity-checked BEFORE anything is replaced: a truncated download or the wrong
     * file entirely must not destroy a working database. Mirrors iOS `SyncView.restore`.
     */
    suspend fun restore(uri: Uri): String = withContext(Dispatchers.IO) {
        if (SyncEngine.isRunning()) return@withContext "Can't restore while a sync is running — pause it first."
        val staging = File(context.cacheDir, "restore-candidate.db")
        try {
            staging.delete()
            context.contentResolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { input.copyTo(it) }
            } ?: return@withContext "Could not read that file."
        } catch (e: Exception) {
            return@withContext "Could not read that file: ${e.message ?: e}"
        }
        try {
            val verdict = databaseIntegrity(staging.absolutePath)
            if (!verdict.lowercase().contains("ok")) {
                staging.delete()
                return@withContext "That file is not a healthy database ($verdict). Nothing changed."
            }
        } catch (_: Throwable) {
            staging.delete()
            return@withContext "That file is not an Open Oura backup. Nothing changed."
        }
        try {
            DB.resetWritableStore(context)
            staging.copyTo(DB.file(context), overwrite = true)
            staging.delete()
        } catch (e: Exception) {
            return@withContext "Restore failed midway: ${e.message ?: e}"
        }
        lastSummary = null
        log("db", "restored the local database from a backup")
        "Restored. The next sync continues from where the backup left off."
    }

    fun resetLocalDatabase() {
        if (SyncEngine.isRunning()) {
            status = "can't reset while a sync is running — wait for it to finish"
            return
        }
        DB.resetWritableStore(context)
        lastSummary = null
        lastSuccessfulSyncAtMs = null
        Prefs.of(context).edit()
            .remove(Prefs.LAST_SUCCESSFUL_SYNC_AT)
            .remove(Prefs.SYNC_INCOMPLETE)
            .remove(Prefs.LAST_AUTOMATIC_ATTEMPT_AT)
            .apply()
        status = "local sync database reset — run Connect & Sync again"
        log("db", "writable sync database reset")
    }
}
