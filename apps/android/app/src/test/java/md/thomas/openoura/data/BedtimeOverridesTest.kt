package md.thomas.openoura.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The override file is read by Rust (`read_bedtime_overrides`) and written by both native
 * clients; Android must produce the same entries and keys as iOS `BedtimeOverrideStore`.
 */
class BedtimeOverridesTest {

    private val night = NightRow(
        date = "2026-03-01", start = "23:30", end = "07:00",
        rawStartDs = 100_000, rawStart = "23:30", capturedUnix = 1_700_000_000,
    )

    @Test
    fun `moving bedtime earlier shifts the raw start by the clock difference`() {
        val e = BedtimeOverrideStore.entryFor(night, "22:45", "07:00")
        assertEquals(100_000 - 45 * 600L, e.start_ds)
        assertEquals(e.start_ds + (8 * 60 + 15) * 600L, e.end_ds)
    }

    @Test
    fun `the start shift takes the short way round midnight`() {
        // 23:30 → 00:15 is +45 min, not −23 h 15 min.
        val e = BedtimeOverrideStore.entryFor(night, "00:15", "07:00")
        assertEquals(100_000 + 45 * 600L, e.start_ds)
    }

    @Test
    fun `the span is clamped to what the core accepts`() {
        val tooShort = BedtimeOverrideStore.entryFor(night, "23:30", "23:40")
        assertEquals(30 * 600L, tooShort.end_ds - tooShort.start_ds)
    }

    @Test
    fun `set writes the iOS keys and clear removes them`() {
        val dir = Files.createTempDirectory("ov").toFile()
        val file = File(dir, BedtimeOverrideStore.FILE)
        BedtimeOverrideStore.set(listOf(file), "2026-03-01", night, "23:00", "07:00")
        val keys = BedtimeOverrideStore.load(file).keys
        assertEquals(setOf("2026-03-01", "100000", "100000:1700000000"), keys)
        // The JSON is the shape Rust reads: an object of {start_ds, end_ds}.
        assertTrue(file.readText().contains("\"start_ds\""))
        BedtimeOverrideStore.clear(listOf(file), "2026-03-01", night)
        assertTrue(BedtimeOverrideStore.load(file).isEmpty())
        dir.deleteRecursively()
    }
}
