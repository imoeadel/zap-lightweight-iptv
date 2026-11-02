package com.moe.zap.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.moe.zap.core.Account
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the login encrypted with a key held in the Android Keystore. Never logs or exposes the password. */
class CredentialStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("account", Context.MODE_PRIVATE)

    /** Cheap, for the main thread: does not touch the Keystore. */
    fun exists() = prefs.contains(KEY_BLOB)

    fun save(account: Account) {
        val json = JSONObject()
            .put("s", account.server)
            .put("u", account.username)
            .put("p", account.password)
            .toString()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(json.toByteArray())
        prefs.edit().putString(KEY_BLOB, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    /** Slow on TV hardware (the Keystore is a separate secure processor): call from a background thread. */
    fun load(): Account? {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        return try {
            val sealed = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_SIZE))
            val j = JSONObject(String(cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE)))
            Account(j.getString("s"), j.getString("u"), j.getString("p"))
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
        runCatching {
            KeyStore.getInstance(STORE).apply { load(null) }.deleteEntry(ALIAS)
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val ALIAS = "zap_account"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val KEY_BLOB = "blob"
        const val IV_SIZE = 12
    }
}
