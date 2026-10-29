package md.thomas.openoura.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ring_timestamp` (ds) is a per-boot relative deciseconds counter: it resets to ~0 every
 * time the ring reboots. Anchoring every ds to one global reference scatters older boots
 * to wildly wrong dates. The epoch-aware mapping exists in THREE places that must agree —
 * `crates/oura-summary/src/ring_time.rs`, `tools/epoch_time.py` and this Kotlin/Swift pair
 * (docs/clients.md). These tests pin the behaviour that matters.
 */
class RingClockTest {

    /** A `time_sync` record (tag 0x42) — the ring's own authoritative ds ↔ UTC anchor. */
    private fun timeSync(ds: Long, unix: Long, cu: Long = unix) = EventStore.Ev(
        ds = ds,
        tag = 0x42,
        json = SummaryJson.parseToJsonElement("""{"unix_time":$unix}""")
            as kotlinx.serialization.json.JsonObject,
        cu = cu,
        body = null,
    )

    private fun event(ds: Long, cu: Long) = EventStore.Ev(ds, 0x50, null, cu, null)

    /** An `rtc_beacon` (tag 0x85) — the ring's own RTC ds ↔ UTC anchor, as authoritative as 0x42. */
    private fun rtcBeacon(ds: Long, unix: Long, cu: Long) = EventStore.Ev(
        ds = ds,
        tag = 0x85,
        json = SummaryJson.parseToJsonElement("""{"unix_time":$unix}""")
            as kotlinx.serialization.json.JsonObject,
        cu = cu,
        body = null,
    )

    /** A `ring_start` (tag 0x41) reboot marker. */
    private fun ringStart(ds: Long, cu: Long) = EventStore.Ev(ds, 0x41, null, cu, null)

    @Test
    fun `a ds maps through its epoch's time_sync anchor`() {
        val base = 1_772_000_000L
        val clock = EventStore.RingClock(listOf(timeSync(1000, base), event(1600, base + 60)))
        // 600 ds after the anchor is 60 seconds later
        assertEquals(base + 60.0, clock.unixSeconds(1600, base + 60), 0.001)
    }

    @Test
    fun `a reboot starts a new epoch anchored independently`() {
        val bootA = 1_772_000_000L
        val bootB = 1_772_600_000L   // a week later in wall-clock terms
        val events = listOf(
            timeSync(500_000, bootA, cu = bootA),
            event(500_600, bootA + 60),
            // ds jumps far backwards: the ring rebooted and its counter restarted
            timeSync(1_000, bootB, cu = bootB),
            event(1_600, bootB + 60),
        )
        val clock = EventStore.RingClock(events)
        // The low ds belongs to the NEW boot, not to the old epoch's early history.
        assertEquals(bootB + 60.0, clock.unixSeconds(1_600, bootB + 60), 0.001)
        // The high ds still resolves through the first boot's anchor.
        assertEquals(bootA + 60.0, clock.unixSeconds(500_600, bootA + 60), 0.001)
    }

    @Test
    fun `capturedUnix picks the right boot when ds ranges overlap`() {
        val bootA = 1_772_000_000L
        val bootB = 1_772_600_000L
        // Two boots that each ran from ds 1_000 to 500_000, so their ds ranges overlap
        // completely. Only the reboot between them (a backward ds jump far beyond the
        // six-hour slack) separates the epochs.
        val events = listOf(
            timeSync(1_000, bootA, cu = bootA),
            event(500_000, bootA + 49_900),
            timeSync(1_000, bootB, cu = bootB),
            event(500_000, bootB + 49_900),
        )
        val clock = EventStore.RingClock(events)
        // Identical ds; only the capture time says which boot it came from.
        assertEquals(bootA + 100.0, clock.unixSeconds(2_000, bootA + 100), 1.0)
        assertEquals(bootB + 100.0, clock.unixSeconds(2_000, bootB + 100), 1.0)
    }

    @Test
    fun `a small backward ds step is not treated as a reboot`() {
        val base = 1_772_000_000L
        // Events can arrive slightly out of ds order within one boot; only a jump beyond
        // the six-hour slack means the counter actually restarted.
        val clock = EventStore.RingClock(
            listOf(timeSync(10_000, base, cu = base), event(9_000, base - 100))
        )
        // Still the same epoch, so it maps through the same anchor rather than becoming
        // its own boot.
        assertEquals(base - 100.0, clock.unixSeconds(9_000, base - 100), 1.0)
    }

    @Test
    fun `a projection more than six hours past its capture time is rejected`() {
        val base = 1_772_000_000L
        // An anchor that would project this event far into the future relative to when the
        // phone actually captured it — the signature of a replayed older boot.
        val clock = EventStore.RingClock(
            listOf(timeSync(1_000, base), event(10_000_000, base + 60))
        )
        val projected = clock.unixSeconds(10_000_000, capturedUnix = base + 60)
        assertTrue(
            "expected a plausible time, got ${projected - base} s past the anchor",
            projected <= base + 60 + 6 * 3600,
        )
    }

    @Test
    fun `latestUnix reports the newest time_sync`() {
        val base = 1_772_000_000L
        val clock = EventStore.RingClock(
            listOf(timeSync(1_000, base), timeSync(2_000, base + 100))
        )
        assertEquals(base + 100, clock.latestUnix)
    }

    @Test
    fun `without any time_sync the clock falls back to capture times`() {
        val base = 1_772_000_000L
        val clock = EventStore.RingClock(listOf(event(1_000, base), event(1_600, base + 60)))
        // No anchors at all, so the epoch's newest capture time carries the mapping.
        assertEquals(base + 60.0, clock.unixSeconds(1_600, base + 60), 1.0)
        assertEquals(base + 60, clock.latestUnix)
    }

    @Test
    fun `multiple ring_start reboots leave the middle interval undated`() {
        // Rust `multiple_reboots_leave_middle_undated`: two reboots between disagreeing
        // anchors; only the interval before the first reboot and after the last one is datable.
        val clock = EventStore.RingClock(
            listOf(
                timeSync(1_000, 1_700_000_000, cu = 1_700_000_000),
                ringStart(2_000, 1_700_100_000),
                ringStart(8_000, 1_700_100_000),
                timeSync(10_000, 1_700_100_000, cu = 1_700_100_000),
            )
        )
        assertEquals(EventStore.ClockSource.UNDATED, clock.resolve(5_000, 1_700_100_000).source)
        assertEquals(1_700_000_050.0, clock.unixSeconds(1_500, 1_700_100_000), 0.01)
        assertEquals(1_700_099_900.0, clock.unixSeconds(9_000, 1_700_100_000), 0.01)
    }

    @Test
    fun `erratic counter between disagreeing anchors is undated`() {
        // Rust `erratic_counter_between_disagreeing_anchors_is_undated`: a fresh ring whose
        // counter raced far ahead of wall time; the pocket between the two time syncs must
        // not be scattered over phantom days.
        val cu = 1_783_000_000L
        val clock = EventStore.RingClock(
            listOf(
                event(20_000, cu),
                timeSync(20_928, 1_782_939_604, cu),
                event(500_000, cu),
                rtcBeacon(1_032_193, 1_782_943_316, cu),
                event(1_100_000, cu),
                rtcBeacon(1_133_000, 1_782_953_397, cu),
                event(1_200_000, cu),
            )
        )
        assertEquals(EventStore.ClockSource.UNDATED, clock.resolve(500_000, cu).source)
        val inside = clock.resolve(1_100_000, cu)
        assertEquals(EventStore.ClockSource.ANCHOR, inside.source)
        assertEquals(1_782_953_397.0 - 3_300.0, inside.unix, 0.01)
        assertEquals(EventStore.ClockSource.ANCHOR, clock.resolve(1_200_000, cu).source)
        assertEquals(EventStore.ClockSource.ANCHOR, clock.resolve(20_000, cu).source)
    }

    @Test
    fun `a stalled counter uses download time to pick the anchor side`() {
        // Rust `stalled_counter_uses_download_time_to_pick_the_anchor_side`: the ring lost
        // ~40 h while off; the download time of an event decides which anchor's offset applies.
        val before = 47_893_458L to 1_787_733_180L
        val after = 61_076_535L to 1_789_195_380L
        val clock = EventStore.RingClock(
            listOf(
                timeSync(before.first, before.second, cu = before.second + 60),
                event(52_000_000, 1_788_100_000),
                timeSync(after.first, after.second, cu = after.second + 60),
            )
        )
        val early = clock.resolve(52_000_000, 1_788_100_000)
        assertEquals(EventStore.ClockSource.ANCHOR, early.source)
        assertEquals(before.second + (52_000_000 - before.first) / 10.0, early.unix, 0.01)
        val late = clock.resolve(52_000_000, after.second + 60)
        assertEquals(EventStore.ClockSource.ANCHOR, late.source)
        assertEquals(after.second - (after.first - 52_000_000) / 10.0, late.unix, 0.01)
    }

    @Test
    fun `a ring_start marks the stall when pre-stall events download late`() {
        // Rust `ring_start_marks_the_stall_when_pre_stall_events_download_late`: every
        // pre-stall event was downloaded after the stall, so download time can't pick the
        // side — the 0x41 reboot does.
        val before = 8_040_603L to 1_788_523_149L
        val boot = 24_041_617L
        val after = 25_724_127L to 1_790_523_679L
        val cu = after.second
        val clock = EventStore.RingClock(
            listOf(
                timeSync(before.first, before.second, cu = before.second),
                event(20_000_000, cu),
                ringStart(boot, cu),
                event(25_000_000, cu),
                timeSync(after.first, after.second, cu = cu),
            )
        )
        val early = clock.resolve(20_000_000, cu)
        assertEquals(EventStore.ClockSource.ANCHOR, early.source)
        assertEquals(before.second + (20_000_000 - before.first) / 10.0, early.unix, 0.01)
        val late = clock.resolve(25_000_000, cu)
        assertEquals(after.second - (after.first - 25_000_000) / 10.0, late.unix, 0.01)
    }

    @Test
    fun `an rtc beacon dates sleep independently of download time`() {
        // Rust `rtc_beacon_dates_overnight_sleep_independently_of_download_time`: a beacon
        // captured ten hours after the sleep still dates it correctly.
        val cu = 1_789_056_420L
        val clock = EventStore.RingClock(
            listOf(
                event(672_400, cu),
                rtcBeacon(1_000_000, 1_789_020_420, cu),
            )
        )
        assertEquals(1_788_987_660.0, clock.unixSeconds(672_400, cu), 0.01)
        assertEquals(1_789_020_420.0, clock.unixSeconds(1_000_000, cu), 0.01)
        assertEquals(1_789_020_420L, clock.latestUnix)
    }

    @Test
    fun `an rtc beacon anchors a new boot instead of reusing the old time_sync`() {
        // Rust `rtc_beacon_anchors_new_boot_instead_of_reusing_old_time_sync`: a low-ds boot
        // must anchor through its own beacon, not the earlier boot's higher-ds time_sync.
        val clock = EventStore.RingClock(
            listOf(
                timeSync(5_000_000, 1_788_800_000, cu = 1_788_800_000),
                event(672_400, 1_789_056_420),
                rtcBeacon(1_000_000, 1_789_020_420, 1_789_056_420),
            )
        )
        assertEquals(1_788_987_660.0, clock.unixSeconds(672_400, 1_789_056_420), 0.01)
        assertEquals(1_788_800_000.0, clock.unixSeconds(5_000_000, 1_788_800_000), 0.01)
        assertEquals(1_789_020_420L, clock.latestUnix)
    }

    @Test
    fun `datedUnixSeconds withholds undated data`() {
        val cu = 1_783_000_000L
        val clock = EventStore.RingClock(
            listOf(
                timeSync(20_928, 1_782_939_604, cu),
                event(500_000, cu),
                rtcBeacon(1_032_193, 1_782_943_316, cu),
            )
        )
        assertEquals(null, clock.datedUnixSeconds(500_000, cu))
        assertEquals(1_782_943_316.0, clock.datedUnixSeconds(1_032_193, cu)!!, 0.01)
    }
}
