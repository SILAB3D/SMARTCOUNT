package com.silab.smartcount.data.api

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.silab.smartcount.util.Pkcs1
import java.util.UUID

/**
 * Guarda la identidad de "instalación" que Tricount asocia a tu cuenta anónima.
 *
 * Importante: si borras estas credenciales pierdes el acceso a los tricounts
 * que se sincronizaron con esa instalación (salvo que vuelvas a unirte con el
 * enlace público). Se guardan cifradas con la keystore del dispositivo.
 */
class CredentialStore private constructor(private val prefs: SharedPreferences) {

    val appId: String
        get() = prefs.getString(KEY_APP_ID, null) ?: error("Credenciales no inicializadas")

    val publicKeyPem: String
        get() = prefs.getString(KEY_PUB_KEY, null) ?: error("Credenciales no inicializadas")

    val exists: Boolean
        get() = prefs.contains(KEY_APP_ID) && prefs.contains(KEY_PUB_KEY)

    fun ensureCreated() {
        if (exists) return
        prefs.edit()
            .putString(KEY_APP_ID, UUID.randomUUID().toString())
            .putString(KEY_PUB_KEY, Pkcs1.generateKeyPairPem())
            .apply()
    }

    /** Restaura credenciales exportadas desde otro dispositivo. */
    fun restore(appId: String, publicKeyPem: String) {
        prefs.edit()
            .putString(KEY_APP_ID, appId)
            .putString(KEY_PUB_KEY, publicKeyPem)
            .apply()
    }

    fun export(): Pair<String, String> = appId to publicKeyPem

    companion object {
        private const val FILE = "tricount_credentials"
        private const val KEY_APP_ID = "app_id"
        private const val KEY_PUB_KEY = "public_key_pem"

        fun create(context: Context): CredentialStore {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            return CredentialStore(prefs).also { it.ensureCreated() }
        }
    }
}
