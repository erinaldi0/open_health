package md.thomas.openoura.ble

import md.thomas.openoura.store.isValidRingKey
import md.thomas.openoura.store.validRingSerial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale

class RingSyncFormatTest {

    // The formatters follow the device locale ("1,0 MB" on an Italian phone is correct),
    // so pin one here instead of letting the developer's machine decide the expectations.
    private val savedLocale = Locale.getDefault()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(savedLocale)

    @Test
    fun `auth rejections are recognised so they are not retried`() {
        // Retrying a deterministic rejection just re-fails and burns the ring's battery.
        assertTrue(SyncEngine.isAuthenticationFailure("protocol error: authentication failed"))
        assertTrue(SyncEngine.isAuthenticationFailure("Ring rejected auth key"))
        assertFalse(SyncEngine.isAuthenticationFailure("BLE link lost mid-batch"))
        assertFalse(SyncEngine.isAuthenticationFailure("extended history request failed with result code 0xff"))
    }

    @Test
    fun `a remembered serial ignores blanks and the unknown placeholder`() {
        // iOS rememberSerial refuses these; the factory-reset gate reads the same value.
        assertEquals("2D1A", validRingSerial("  2D1A  "))
        assertEquals(null, validRingSerial(null))
        assertEquals(null, validRingSerial("   "))
        assertEquals(null, validRingSerial("unknown"))
        assertEquals(null, validRingSerial("Unknown"))
    }

    @Test
    fun `factory-reset needs a known serial and a valid key`() {
        val key = "0123456789abcdef0123456789ABCDEF"
        assertTrue(SyncEngine.canFactoryReset("2D1A", key))
        assertFalse(SyncEngine.canFactoryReset(null, key))       // no serial seen yet
        assertFalse(SyncEngine.canFactoryReset("2D1A", "short")) // no key to authenticate
    }

    @Test
    fun `the ring key must be exactly 32 hex characters`() {
        // Rust's parse_key enforces the same rule; rejecting early gives a better message.
        assertTrue(isValidRingKey("0123456789abcdef0123456789ABCDEF"))
        assertTrue(isValidRingKey("  0123456789abcdef0123456789abcdef  "))
        assertFalse(isValidRingKey("0123456789abcdef0123456789abcde"))   // 31
        assertFalse(isValidRingKey("0123456789abcdef0123456789abcdef0")) // 33
        assertFalse(isValidRingKey("0123456789abcdef0123456789abcdeg"))  // non-hex
        assertFalse(isValidRingKey(""))
    }

    @Test
    fun `byte counts read as KB below a megabyte and MB above`() {
        assertEquals("512 KB", SyncEngine.fmtBytes(524_288uL))
        assertEquals("1.0 MB", SyncEngine.fmtBytes(1_048_576uL))
        assertEquals("2.5 MB", SyncEngine.fmtBytes(2_621_440uL))
    }

    @Test
    fun `durations read in seconds, minutes then hours`() {
        assertEquals("1s", SyncEngine.fmtDuration(0.0))   // never "0s"
        assertEquals("45s", SyncEngine.fmtDuration(45.0))
        assertEquals("2 min", SyncEngine.fmtDuration(120.0))
        assertEquals("1.5 h", SyncEngine.fmtDuration(5400.0))
    }
}
