package dz.association.app.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets (tokens) before they are written to disk. */
interface Crypto {
    fun encrypt(plain: String): String
    fun decrypt(cipherText: String): String?
}

/** AES-256-GCM with a non-exportable key kept in the Android Keystore. */
class KeystoreCrypto(private val alias: String = "association_tokens") : Crypto {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    override fun decrypt(cipherText: String): String? = runCatching {
        val raw = Base64.decode(cipherText, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    }.getOrNull()
}

/** Token persistence. Values are encrypted; the preferences file is excluded from backups. */
class TokenStore(context: Context, private val crypto: Crypto) {
    private val prefs = context.getSharedPreferences("secure_session", Context.MODE_PRIVATE)

    fun read(key: String): String? = prefs.getString(key, null)?.let(crypto::decrypt)

    fun write(values: Map<String, String?>) {
        prefs.edit().apply {
            values.forEach { (k, v) -> if (v == null) remove(k) else putString(k, crypto.encrypt(v)) }
        }.apply()
    }

    fun clear() = prefs.edit().clear().apply()
}
