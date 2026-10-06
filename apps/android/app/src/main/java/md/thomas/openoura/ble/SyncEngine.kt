package md.thomas.openoura.ble

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import md.thomas.openoura.diag.Diagnostics.log
import md.thomas.openoura.store.DB
import md.thomas.openoura.store.Prefs
import md.thomas.openoura.store.RingKeyStore
import md.thomas.openoura.store.isValidRingKey
import md.thomas.openoura.store.secretFingerprint
import uniffi.oura_core.BleWriter
import uniffi.oura_core.HeartRateListener
import uniffi.oura_core.RingSession
import uniffi.oura_core.SyncProgressListener
import uniffi.oura_core.SyncReport
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Headless BLE sync engine. Connect to the ring over BluetoothGatt ([BleTransport]), then
 * drive the SAME Rust client over FFI ([RingSession]) to authenticate + drain history
 * events into the writable SQLite DB. Mirrors `oura sync` on desktop and RingSync.swift on
 * iOS. The BLE round-trip only works against a physical ring.
 *
 * Extracted off the ViewModel so it can run from [RingSyncWorker] with only an application
 * Context — no Compose state, no `viewModelScope`. All progress is reported through the
 * `onStatus` callback the caller passes in (the worker forwards it to `setProgress` and the
 * ongoing notification; a foreground caller can drop it into Compose state).
 */
class SyncEngine(private val context: Context) {

    private var transport: BleTransport? = null
    private var lastProgressBytes: ULong? = null
    private var lastProgressAtMs: Long? = null
    private var smoothedBytesPerSecond: Double? = null
    private var markedIncompleteThisRun = false

    companion object {
        /**
         * A launch/foreground refresh is useful, but reconnecting twice while someone
         * briefly switches apps is not. Manual sync remains available at any time.
         */
        const val AUTOMATIC_SYNC_COOLDOWN_MS = 3L * 60 * 1000

        private const val FAILED_ATTEMPT_COOLDOWN_MS = 60L * 1000

        /** Manual/automatic/resume keep the original fixed 3 s pause between attempts. */
        val FIXED_3S: (Int) -> Long = { 3_000L }

        /** Manual sync retries a handful of times, like iOS. */
        const val MANUAL_MAX_ATTEMPTS = 6

        /**
         * The scheduled 6-hour run is patient about a ring that is momentarily unreachable:
         * exponential backoff before attempts 2..5 — 5 s, 10 s, 20 s, 40 s — each capped at
         * one minute. Attempt 1 has no preceding wait, so [SCHEDULED_BACKOFF] is only ever
         * asked for attempt >= 2.
         */
        val SCHEDULED_BACKOFF: (Int) -> Long = { attempt ->
            min(60_000L, 5_000L shl (attempt - 2))
        }
        const val SCHEDULED_MAX_ATTEMPTS = 5

        /** Battery backstop for a live HR stream the UI forgets to stop. */
        const val LIVE_HR_MAX_SECONDS: UInt = 1800u

        // Process-wide single-flight across the periodic worker, the app-start/manual
        // one-shots, and any overlap between them. tryLock() == false is exactly the
        // "a background sync is already running" the caller wants to skip on.
        private val runLock = Mutex()

        fun isRunning(): Boolean = runLock.isLocked

        internal fun isAuthenticationFailure(message: String): Boolean {
            val s = message.lowercase()
            return s.contains("authentication failed") || s.contains("ring rejected auth")
        }

        internal fun fmtBytes(b: ULong): String =
            if (b >= 1_048_576uL) "%.1f MB".format(b.toDouble() / 1_048_576)
            else "%.0f KB".format(b.toDouble() / 1024)

        internal fun fmtDuration(seconds: Double): String {
            val s = max(1, seconds.roundToInt())
            if (s < 60) return "${s}s"
            val minutes = (s / 60.0).roundToInt()
            if (minutes < 60) return "$minutes min"
            return "%.1f h".format(minutes / 60.0)
        }
    }

    private val hasIncompleteSync: Boolean
        get() = Prefs.of(context).getBoolean(Prefs.SYNC_INCOMPLETE, false)

    private fun lastSuccessfulSyncAtMs(): Long? =
        Prefs.of(context).getLong(Prefs.LAST_SUCCESSFUL_SYNC_AT, 0L).takeIf { it > 0 }

    /**
     * Has THIS key completed a sync on this device before? The key is only persisted after
     * a successful sync, so a stored key that matches is one the ring has already accepted.
     */
    private fun keyHasWorkedBefore(key: String): Boolean =
        lastSuccessfulSyncAtMs() != null && RingKeyStore.load(context) == key

    private fun clearIncompleteSync() {
        Prefs.of(context).edit().remove(Prefs.SYNC_INCOMPLETE).apply()
    }

    /**
     * Opportunistic refresh used at launch and when returning to the app. It never prompts
     * for a key. Normally a timid single attempt behind a cooldown — but when the last sync
     * was interrupted mid-drain, the checkpointed cursor means data is sitting
     * half-transferred on the ring, so resume eagerly with the retry loop instead of
     * silently giving up.
     */
    suspend fun syncAutomaticallyIfNeeded(
        onStatus: (String) -> Unit,
        nowMs: Long = System.currentTimeMillis(),
    ): SyncReport? {
        val key = RingKeyStore.load(context) ?: return null
        if (!isValidRingKey(key)) return null
        val resuming = hasIncompleteSync
        if (!resuming) {
            val successful = lastSuccessfulSyncAtMs()
            if (successful != null && nowMs - successful < AUTOMATIC_SYNC_COOLDOWN_MS) return null
        }
        // A failed scan should not immediately restart because the app was resumed.
        val prefs = Prefs.of(context)
        val lastAttempt = prefs.getLong(Prefs.LAST_AUTOMATIC_ATTEMPT_AT, 0L)
        if (lastAttempt > 0 && nowMs - lastAttempt < FAILED_ATTEMPT_COOLDOWN_MS) return null
        prefs.edit().putLong(Prefs.LAST_AUTOMATIC_ATTEMPT_AT, nowMs).apply()
        if (resuming) onStatus("resuming interrupted sync from checkpoint…")
        return run(
            maxAttempts = if (resuming) 3 else 1,
            source = if (resuming) "resume" else "automatic",
            backoff = FIXED_3S,
            onStatus = onStatus,
        )
    }

    /**
     * Connect, wire the inbound-frame pump, and run a full sync into the writable DB. The
     * ring auth key is read from the Keystore here (never passed in) so it never lands in
     * WorkManager's Data. Returns null — without touching the process lock — if a sync is
     * already running.
     */
    suspend fun run(
        maxAttempts: Int,
        source: String,
        backoff: (attempt: Int) -> Long,
        onStatus: (String) -> Unit,
    ): SyncReport? {
        if (!runLock.tryLock()) {
            log("sync", "run source=$source skipped — a sync is already in progress")
            return null
        }
        try {
            return runLocked(maxAttempts, source, backoff, onStatus)
        } finally {
            runLock.unlock()
        }
    }

    private suspend fun runLocked(
        maxAttempts: Int,
        source: String,
        backoff: (attempt: Int) -> Long,
        onStatus: (String) -> Unit,
    ): SyncReport? = coroutineScope {
        lastProgressBytes = null
        lastProgressAtMs = null
        smoothedBytesPerSecond = null
        markedIncompleteThisRun = false

        val key = RingKeyStore.load(context)?.trim()
        if (key == null) {
            onStatus("no ring key stored yet — open Sync and paste your 32-hex key")
            return@coroutineScope null
        }
        // A SHA-256-derived fingerprint confirms the *right* key arrived intact without
        // exposing any key bytes (a raw slice would leak key material).
        log("sync", "run source=$source — key len=${key.length}, fp(sha256)=${secretFingerprint(key)}")
        if (!isValidRingKey(key)) {
            log("sync", "rejected key: len=${key.length} (need 32 hex chars)")
            onStatus("key must be 32 hex characters")
            return@coroutineScope null
        }
        if (BlePermissions.missing(context).isNotEmpty()) {
            onStatus("Bluetooth permission is required — grant it and try again")
            return@coroutineScope null
        }

        // A multi-hour first sync must not die because the screen locked. Unlike iOS's
        // idle-timer trick this is a real CPU wake lock, so it also survives the app going
        // to the background alongside the foreground service WorkManager runs us on.
        WakeLockOwner.acquire(context, "ring-sync")
        var pump: Job? = null
        try {
            // The drain checkpoints its cursor after every batch, so each retry RESUMES
            // where the link dropped rather than starting over — reconnect-and-retry is
            // safe and cheap. Retries cover both connect failures and mid-sync drops.
            for (attempt in 1..maxAttempts) {
                if (attempt > 1) {
                    val wait = backoff(attempt)
                    log("sync", "attempt $attempt/$maxAttempts — resuming from the checkpointed cursor in ${wait / 1000} s")
                    onStatus("connection lost — resuming (attempt $attempt/$maxAttempts)…")
                    delay(wait)
                }

                onStatus(if (attempt == 1) "connecting to ring…" else "reconnecting to ring…")
                log("sync", "connecting — scanning for the Oura service (name filter 'Oura')…")

                // Fresh transport per attempt: the previous link is dead and the
                // notification stream is per-connection.
                val t = BleTransport(context, nameContains = "Oura")
                transport = t
                try {
                    t.connect()
                } catch (e: Throwable) {
                    log("sync", "BLE connect FAILED: $e")
                    // The ring advertises reliably only ON its charger (low-power adv when
                    // worn), and it has a single BLE link — a phone running the official app
                    // holds it, leaving nothing to discover.
                    onStatus(
                        "couldn't connect (${e.message ?: e}) — put the ring on its charger " +
                            "and turn off Bluetooth on the phone with the official Oura app"
                    )
                    t.shutdown()
                    transport = null
                    continue
                }
                log("sync", "BLE link ready — creating RingSession + inbound-frame pump")

                val session = RingSession(RingWriter(t, this))
                pump?.cancelAndJoin()
                pump = launch(Dispatchers.IO) {
                    t.notifications().consumeEach { session.pushFrame(it) }
                }

                onStatus("syncing…")
                log("sync", "starting FFI sync() — authenticate, app stream, then event drain")
                try {
                    val report = withContext(Dispatchers.IO) {
                        session.sync(
                            dbPath = DB.writePath(context),
                            keyHex = key,
                            progress = ProgressBridge(onStatus),
                        )
                    }
                    RingKeyStore.save(context, key)
                    val completedAt = System.currentTimeMillis()
                    Prefs.of(context).edit()
                        .putLong(Prefs.LAST_SUCCESSFUL_SYNC_AT, completedAt)
                        .remove(Prefs.SYNC_INCOMPLETE)
                        .apply()
                    log(
                        "sync",
                        "OK — serial=${report.serial} inserted=${report.inserted} " +
                            "events=${report.eventsSynced} cursor=${report.nextCursor}"
                    )
                    onStatus("synced — ${report.inserted} new events from ${report.serial}")
                    return@coroutineScope report
                } catch (e: CancellationException) {
                    // Paused (or the worker was stopped). Tell Rust explicitly so it stops at
                    // its next await instead of mid-request; the drain checkpointed every
                    // batch, so the next sync resumes from here. Not an attempt failure —
                    // falling into the catch-all below would log it as one and retry.
                    log("sync", "attempt $attempt cancelled — ${e.message ?: "paused"}")
                    session.cancel("paused")
                    throw e
                } catch (e: Throwable) {
                    // The Rust layer packs the diagnostic detail (auth state, missing
                    // summary, cursor) into this message — log it verbatim.
                    val message = e.message ?: e.toString()
                    log("sync", "attempt $attempt FAILED: $message")
                    pump?.cancelAndJoin()
                    pump = null
                    t.disconnect() // release the (possibly half-dead) link before retrying
                    if (isAuthenticationFailure(message)) {
                        // A key that has authenticated before cannot suddenly be the wrong
                        // key. The ring serves one client session at a time, so a rejection
                        // here means something else holds it — in practice the official Oura
                        // app. Saying "bad key" in that case sends people off to re-extract a
                        // key that was already correct.
                        onStatus(
                            if (keyHasWorkedBefore(key)) {
                                "the ring rejected authentication, but this key has worked before " +
                                    "— another app is holding the ring's session. Force-stop the " +
                                    "official Oura app and try again"
                            } else {
                                "auth failed — this key was rejected by the ring; paste the key " +
                                    "exported from the phone that onboarded this exact ring"
                            }
                        )
                        log("sync", "not retrying: auth rejection is deterministic")
                        // Deterministic rejection — an eager resume would just re-fail.
                        clearIncompleteSync()
                        return@coroutineScope null
                    }
                    onStatus("sync interrupted: $message")
                } finally {
                    session.close()
                }
            }

            onStatus(
                when (source) {
                    "automatic" -> "automatic sync couldn't reach the ring — tap the sync icon for details"
                    "resume" -> "couldn't resume the interrupted sync — it will retry when you return to the app"
                    "scheduled" -> "scheduled sync couldn't reach the ring — it will try again in a few hours"
                    else -> "sync failed after $maxAttempts attempts — progress is saved, run sync again to resume"
                }
            )
            log("sync", "giving up after $maxAttempts attempts — cursor is checkpointed, next sync resumes")
            return@coroutineScope null
        } finally {
            // Cancel the pump and drop the ring's single BLE link before releasing the lock:
            // holding it would stop the ring advertising for the official app, the Mac, AND
            // our own next scan (it would look like "no ring advertisement seen").
            pump?.cancelAndJoin()
            WakeLockOwner.release("ring-sync")
            transport?.shutdown()
            transport = null
        }
    }

    /**
     * Stream live heart rate from the worn ring until the calling coroutine is cancelled
     * (or [LIVE_HR_MAX_SECONDS] elapses). Takes the same single-flight lock as [run]: the
     * ring has one BLE link, so a live stream and a sync cannot share it.
     *
     * Cancellation must not reach the FFI call — UniFFI frees the Rust future on cancel,
     * which would skip its restore and leave the ring's daytime HR in live mode (draining
     * its battery). So the call runs in its own scope; on cancel we signal
     * [RingSession.stopLiveHeartRate], await the restore, and flush it to the ring before
     * dropping the link.
     */
    suspend fun liveHeartRate(
        onHeartRate: (bpm: Int, ibiMs: Int) -> Unit,
        onStatus: (String) -> Unit,
    ) {
        if (!runLock.tryLock()) {
            onStatus("a ring sync is in progress — try again when it finishes")
            return
        }
        try {
            val key = RingKeyStore.load(context)?.trim()
            if (key == null || !isValidRingKey(key)) {
                onStatus("no valid ring key stored yet — open Sync and paste your 32-hex key")
                return
            }
            if (BlePermissions.missing(context).isNotEmpty()) {
                onStatus("Bluetooth permission is required — grant it and try again")
                return
            }

            onStatus("connecting to ring…")
            val t = BleTransport(context, nameContains = "Oura")
            try {
                t.connect()
            } catch (e: CancellationException) {
                t.shutdown()
                throw e
            } catch (e: Throwable) {
                log("live-hr", "BLE connect FAILED: $e")
                onStatus(
                    "couldn't connect (${e.message ?: e}) — make sure the ring is nearby and " +
                        "the official Oura app isn't holding it"
                )
                t.shutdown()
                return
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val writer = RingWriter(t, scope)
            val session = RingSession(writer)
            scope.launch { t.notifications().consumeEach { session.pushFrame(it) } }
            val listener = object : HeartRateListener {
                override fun onStatus(stage: String) {
                    onStatus(
                        when (stage) {
                            "auth" -> "authenticating…"
                            "setup" -> "preparing ring…"
                            "live" -> "waiting for a heartbeat — keep the ring on and stay still"
                            else -> stage
                        }
                    )
                }

                override fun onSample(bpm: UShort, ibiMs: UShort) {
                    onHeartRate(bpm.toInt(), ibiMs.toInt())
                }
            }
            val call = scope.async {
                session.liveHeartRate(keyHex = key, maxSeconds = LIVE_HR_MAX_SECONDS, listener = listener)
            }
            try {
                call.await()
                log("live-hr", "stream ended (time limit)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val message = e.message ?: e.toString()
                log("live-hr", "FAILED: $message")
                onStatus(
                    if (isAuthenticationFailure(message)) {
                        "the ring rejected authentication — force-stop the official Oura app and try again"
                    } else {
                        "live heart rate failed: $message"
                    }
                )
            } finally {
                withContext(NonCancellable) {
                    session.stopLiveHeartRate()
                    // Both bounded: a dead link must not wedge the lock forever.
                    withTimeoutOrNull(5_000) { runCatching { call.await() } }
                    withTimeoutOrNull(3_000) { writer.flush() }
                    scope.cancel()
                    session.close()
                    t.shutdown()
                }
            }
        } finally {
            runLock.unlock()
        }
    }

    /** Bridges Rust sync-progress callbacks (arriving on a tokio thread) to [onStatus]. */
    private inner class ProgressBridge(private val onStatus: (String) -> Unit) : SyncProgressListener {
        override fun onProgress(stage: String, bytesLeft: ULong, eventsSynced: UInt) {
            showProgress(stage, bytesLeft, eventsSynced, onStatus)
        }
    }

    /** Render Rust-side progress into a status line. */
    private fun showProgress(stage: String, bytesLeft: ULong, events: UInt, onStatus: (String) -> Unit) {
        log("progress", "stage=$stage bytesLeft=$bytesLeft events=$events")
        // Real drain progress means data is mid-transfer: from here until the sync completes,
        // an interruption should resume eagerly on return to the app.
        if (events > 0u && !markedIncompleteThisRun) {
            markedIncompleteThisRun = true
            Prefs.of(context).edit().putBoolean(Prefs.SYNC_INCOMPLETE, true).apply()
        }
        when (stage) {
            "auth" -> onStatus("authenticating…")
            "setup" -> onStatus("configuring ring…")
            "rebase" -> {
                onStatus("ring clock reset detected — recovering history…")
                log("sync", "saved cursor is absent on ring — rebasing to the new boot epoch")
            }
            else -> onStatus(drainStatus(bytesLeft, events))
        }
    }

    private fun drainStatus(bytesLeft: ULong, events: UInt): String {
        if (bytesLeft == 0uL) {
            return if (events > 0u) "syncing… $events events · finishing up" else "syncing…"
        }
        val now = System.currentTimeMillis()
        val previousBytes = lastProgressBytes
        val previousAt = lastProgressAtMs
        if (previousBytes != null && previousAt != null && previousBytes > bytesLeft) {
            val elapsed = (now - previousAt) / 1000.0
            if (elapsed > 0.05) {
                val instant = (previousBytes - bytesLeft).toDouble() / elapsed
                smoothedBytesPerSecond =
                    smoothedBytesPerSecond?.let { it * 0.65 + instant * 0.35 } ?: instant
            }
        } else if (previousBytes != null && bytesLeft > previousBytes) {
            smoothedBytesPerSecond = null // a rebase/new drain starts a new estimate
        }
        lastProgressBytes = bytesLeft
        lastProgressAtMs = now
        val eta = smoothedBytesPerSecond
            ?.takeIf { it > 0 }
            ?.let { fmtDuration(bytesLeft.toDouble() / it) }
        return "syncing… ~${fmtBytes(bytesLeft)} left · $events events" +
            (eta?.let { " · about $it" } ?: "")
    }
}

/**
 * Bridges the Rust BleWriter callback onto BleTransport's suspending write. The callback is
 * synchronous (Rust's transact then waits for the response via push_frame), but a GATT
 * write-with-response must complete before the next one or the stack rejects it as busy. So
 * writes are chained into a FIFO: each awaits the previous one's completion, guaranteeing
 * strictly sequential, non-overlapping writes. Port of `RingWriter`.
 */
internal class RingWriter(
    private val transport: BleTransport,
    private val scope: CoroutineScope,
) : BleWriter {
    private val lock = Any()
    private var tail: Job = Job().apply { complete() }

    override fun write(data: ByteArray) {
        synchronized(lock) {
            val prev = tail
            tail = scope.launch(Dispatchers.IO) {
                prev.join() // wait for the prior write to finish…
                try {
                    transport.write(data) // …then perform (and await) this one
                } catch (e: Throwable) {
                    // A failed write means the ring never got the frame — close the inbound
                    // stream so the Rust drain stops waiting and the sync fails loudly
                    // instead of proceeding as if the request was sent.
                    log("write", "FAILED ($e) — aborting inbound stream so the sync errors out")
                    transport.abort()
                }
            }
        }
    }

    /** Suspend until every write queued so far has gone out (or failed). */
    suspend fun flush() {
        synchronized(lock) { tail }.join()
    }
}
