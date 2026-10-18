package dev.codex.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.codex.widget.data.SecureStateStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Requires a device/emulator: this exercises Android Keystore, never a JVM fake. */
@RunWith(AndroidJUnit4::class)
class SecureStorageDeviceTest {
    @Test fun encryptedStateSurvivesNewInstanceAndRejectsTampering() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecureStateStore(context, "instrumentation-state.aes")
        store.clear()
        try {
            store.save(JSONObject().put("token", "instrumentation-test-not-a-real-token"))
            val file = File(context.noBackupFilesDir, "instrumentation-state.aes")
            val ciphertext = file.readBytes()
            assertFalse(String(ciphertext).contains("instrumentation-test-not-a-real-token"))
            assertEquals("instrumentation-test-not-a-real-token", SecureStateStore(context, "instrumentation-state.aes").read().getString("token"))
            ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
            file.writeBytes(ciphertext)
            assertThrows(Exception::class.java) { SecureStateStore(context, "instrumentation-state.aes").read() }
        } finally { store.clear() }
        assertEquals(0, store.read().length())
    }
}
