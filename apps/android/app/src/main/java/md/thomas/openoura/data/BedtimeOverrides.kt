package md.thomas.openoura.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Manual bedtime / wake-up corrections. The shared core reads `bedtime_overrides.json` next
 * to the DB (`oura_summary::read_bedtime_overrides`) and applies an entry to the night whose
 * `raw_start_ds[:captured_unix]` (or `start_ds[:captured_unix]`) matches, marking it
 * `bedtime_manual`. Not an FFI call, so the client writes the file — with exactly the keys
 * and entry shape iOS `BedtimeOverrideStore` (Models.swift) writes, so a DB moved between
 * clients keeps its corrections.
 */
object BedtimeOverrideStore {

    @Serializable
    data class Entry(
        val start_ds: Long,
        val end_ds: Long,
        val start: String? = null,
        val end: String? = null,
    )

    const val FILE = "bedtime_overrides.json"

    private val mapSerializer = MapSerializer(String.serializer(), Entry.serializer())

    fun load(file: File): Map<String, Entry> = try {
        if (!file.exists()) emptyMap() else SummaryJson.decodeFromString(mapSerializer, file.readText())
    } catch (_: Exception) {
        emptyMap()
    }

    /**
     * The entry for moving [night] to [startHM]–[endHM]. The new start is the night's raw
     * start shifted by the clock difference (taken the short way round midnight); the span
     * is clamped to 30 min–20 h, the range the core accepts.
     */
    fun entryFor(night: NightRow, startHM: String, endHM: String): Entry {
        val baseStartDs = night.rawStartDs ?: night.startDs ?: 10_000
        val baseStartMin = hmMinutes(night.rawStart ?: night.start ?: "00:00")
        val newStartMin = hmMinutes(startHM)
        val newEndMin = hmMinutes(endHM)
        var delta = (newStartMin - baseStartMin) % 1440
        if (delta > 720) delta -= 1440
        if (delta < -720) delta += 1440
        val spanMin = if (newEndMin > newStartMin) newEndMin - newStartMin else newEndMin + 1440 - newStartMin
        val startDs = baseStartDs + delta * 600L
        val endDs = startDs + max(30, min(1200, spanMin)) * 600L
        return Entry(startDs, endDs, startHM, endHM)
    }

    /** Every key the core may look this night up by, plus the UI's night key (as iOS). */
    internal fun keysFor(nightKey: String, night: NightRow): List<String> {
        val raw = night.rawStartDs ?: night.startDs ?: return listOf(nightKey)
        return listOfNotNull(nightKey, "$raw", night.capturedUnix?.let { "$raw:$it" })
    }

    /** Write the override to every file in [files] (the read DB's folder and the writable one). */
    fun set(files: List<File>, nightKey: String, night: NightRow, startHM: String, endHM: String) {
        val entry = entryFor(night, startHM, endHM)
        for (file in files.distinct()) {
            val map = load(file).toMutableMap()
            for (k in keysFor(nightKey, night)) map[k] = entry
            write(file, map)
        }
    }

    fun clear(files: List<File>, nightKey: String, night: NightRow) {
        for (file in files.distinct()) {
            val map = load(file).toMutableMap()
            for (k in keysFor(nightKey, night)) map.remove(k)
            write(file, map)
        }
    }

    private fun write(file: File, map: Map<String, Entry>) {
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeText(SummaryJson.encodeToString(mapSerializer, map.toSortedMap()))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    internal fun hmMinutes(hm: String?): Int {
        val parts = (hm ?: "00:00").split(":").mapNotNull { it.toIntOrNull() }
        return (parts.firstOrNull() ?: 0) * 60 + (parts.getOrNull(1) ?: 0)
    }
}
