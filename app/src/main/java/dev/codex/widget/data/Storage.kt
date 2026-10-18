package dev.codex.widget.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.codex.widget.domain.ResetCredit
import dev.codex.widget.domain.UsageSnapshot
import dev.codex.widget.domain.UsageWindow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface StateStore {
    fun read(): JSONObject
    fun save(state: JSONObject)
    fun clear()
}

/** The whole state, including refresh tokens, cache and reminder ledger, is encrypted. */
class SecureStateStore(context: Context, fileName: String = "state.aes") : StateStore {
    init { require(fileName.matches(Regex("[a-zA-Z0-9_.-]+"))) }
    private val file = AtomicFile(File(context.noBackupFilesDir, fileName))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized override fun read(): JSONObject {
        if (!file.baseFile.exists()) return JSONObject()
        val bytes = file.readFully()
        require(bytes.size > 28 && bytes[0] == 1.toByte()) { "Invalid secure storage" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8))
    }
    @Synchronized override fun save(state: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(state.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    @Synchronized override fun clear() { file.delete() }
    companion object { private const val ALIAS = "codex.widget.state.v1" }
}

object SnapshotCodec {
    private fun window(value: UsageWindow?) = value?.let {
        JSONObject().put("remaining", it.remaining).put("reset", it.resetsAt ?: JSONObject.NULL)
    } ?: JSONObject.NULL
    fun encode(s: UsageSnapshot): JSONObject = JSONObject()
        .put("five", window(s.fiveHours)).put("weekly", window(s.weekly))
        .put("count", s.availableCount ?: JSONObject.NULL)
        .put("credits", s.credits?.let { rows -> JSONArray().apply {
            rows.forEach { put(JSONObject().put("id", it.id).put("status", it.status)
                .put("expires", it.expiresAt ?: JSONObject.NULL)) }
        } } ?: JSONObject.NULL)
        .put("fetched", s.fetchedAt).put("source", s.source)
    fun decode(root: JSONObject?): UsageSnapshot? = root?.let { s ->
        fun w(key: String) = s.optJSONObject(key)?.let {
            UsageWindow(it.getInt("remaining"), if (it.isNull("reset")) null else it.getLong("reset"))
        }
        val credits = s.optJSONArray("credits")?.let { rows -> (0 until rows.length()).map {
            val row = rows.getJSONObject(it)
            ResetCredit(row.getString("id"), row.getString("status"),
                if (row.isNull("expires")) null else row.getLong("expires"))
        } }
        UsageSnapshot(w("five"), w("weekly"), if (s.isNull("count")) null else s.getInt("count"),
            credits, s.getLong("fetched"), s.getString("source"))
    }
}
