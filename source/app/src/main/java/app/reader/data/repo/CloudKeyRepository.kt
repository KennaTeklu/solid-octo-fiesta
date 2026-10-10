package app.reader.data.repo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.cloudKeyStore: DataStore<Preferences> by preferencesDataStore(name = "reader_cloud_keys")

/**
 * Encrypted key/value store for cloud-voice API credentials (Batch 7 — INT-10).
 *
 * Why not [androidx.security.crypto.EncryptedSharedPreferences]? It pulls in
 * Tink and a multi-megabyte dependency tree for an offline TTS reader that
 * has no other use for it. We need four fields; we can do that with the
 * Android Keystore directly and ~80 lines of code.
 *
 * Design (per EXPERT-MAP #54 — Encryption-at-Rest Specialist):
 *  - A single AES/GCM key is generated on first use and stored in the
 *    Android Keystore under alias `reader_cloud_keys_v1`. The key material
 *    never leaves the keystore.
 *  - Each value is encrypted with a fresh IV (12 bytes, GCM standard).
 *    Ciphertext + IV are base64-encoded and stored in a separate DataStore
 *    file (`reader_cloud_keys.preferences_pb`). The DataStore file itself
 *    is on disk, so the encryption is what protects the keys at rest.
 *  - The clear-text key is never written to disk; it is only held in memory
 *    briefly while decrypting for the upcoming cloud-TTS batch.
 *
 * Threat model: protects against casual disk extraction and backup reads.
 * Does NOT protect against a rooted device with the keystore unlocked — the
 * app process can always decrypt its own data.
 *
 * Batch 7 only WRITES and STORES these fields. Nothing reads them yet — the
 * cloud-TTS integration is Batch 8.
 */
class CloudKeyRepository private constructor(
    private val ds: DataStore<Preferences>
) {

    /** Plaintext key for the in-memory cache after first read. */
    private val cache = HashMap<Key, String?>()

    enum class Key(val prefsKey: String, val label: String) {
        OPENAI_API_KEY("openai_key", "OpenAI API key"),
        AZURE_API_KEY("azure_key", "Azure Speech key"),
        POLLY_API_KEY("polly_key", "Amazon Polly key"),
        CUSTOM_ENDPOINT_URL("custom_url", "Custom endpoint URL"),
        CUSTOM_ENDPOINT_KEY("custom_key", "Custom endpoint key"),
        CUSTOM_ENDPOINT_MODEL("custom_model", "Custom endpoint model")
    }

    fun observe(key: Key): Flow<String?> =
        ds.data.map { prefs ->
            prefs[stringPreferencesKey(key.prefsKey)]?.let { decrypt(it) }
        }

    /** Read once and cache; safe to call from a coroutine on first use. */
    suspend fun get(key: Key): String? {
        cache[key]?.let { return it }
        // ds.data is a Flow<Preferences> — we need .first() to get the
        // current snapshot, then index into it.
        val prefs = ds.data.first()
        val stored = prefs[stringPreferencesKey(key.prefsKey)]
        val plain = stored?.let { decrypt(it) }
        cache[key] = plain
        return plain
    }

    suspend fun set(key: Key, value: String?) {
        if (value.isNullOrBlank()) {
            ds.edit { it.remove(stringPreferencesKey(key.prefsKey)) }
            cache[key] = null
        } else {
            val cipherText = encrypt(value)
            ds.edit { it[stringPreferencesKey(key.prefsKey)] = cipherText }
            cache[key] = value
        }
    }

    // --- Crypto helpers ---

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)  // forces fresh IV per encryption
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // Pack iv || cipherText, base64-encoded.
        val combined = ByteArray(iv.size + cipherText.size).also {
            System.arraycopy(iv, 0, it, 0, iv.size)
            System.arraycopy(cipherText, 0, it, iv.size, cipherText.size)
        }
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        return try {
            val combined = Base64.decode(stored, Base64.NO_WRAP)
            if (combined.size < IV_LENGTH + 1) return ""
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val cipherText = combined.copyOfRange(IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance(AES_GCM_NOPADDING)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "reader_cloud_keys_v1"
        private const val AES_GCM_NOPADDING = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val GCM_TAG_LENGTH_BITS = 128

        @Volatile
        private var instance: CloudKeyRepository? = null
        fun get(context: Context): CloudKeyRepository =
            instance ?: synchronized(this) {
                instance ?: CloudKeyRepository(context.applicationContext.cloudKeyStore).also { instance = it }
            }
    }
}
