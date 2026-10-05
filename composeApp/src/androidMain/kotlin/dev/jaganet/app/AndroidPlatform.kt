package dev.jaganet.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.jaganet.api.Platform
import dev.jaganet.app.platform.AppPlatform
import dev.jaganet.app.platform.SecureStore
import dev.jaganet.app.tunnel.TunnelEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Values are encrypted with an AES key that never leaves the Android Keystore,
 * then kept in app-private SharedPreferences.
 */
class KeystoreStore(context: Context) : SecureStore {
    private val prefs = context.getSharedPreferences("jaganet.secure", Context.MODE_PRIVATE)
    private val alias = "jaganet.store"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    override fun get(key: String): String? = prefs.getString(key, null)?.let { stored ->
        runCatching {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12))
        }.getOrNull()
    }

    override fun set(key: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(value.toByteArray())
        prefs.edit().putString(key, Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

class AndroidPlatform private constructor(private val context: Context, override val apiUrl: String) : AppPlatform {
    /** Set by the visible activity: shows the VPN consent dialog, returns whether it was granted. */
    var vpnConsent: (suspend () -> Boolean)? = null

    override val kind = Platform.ANDROID
    override val deviceName: String = Build.MODEL ?: "Android"
    override val store: SecureStore = KeystoreStore(context)
    override val tunnel: TunnelEngine = AndroidTunnelEngine(context, store) { vpnConsent?.invoke() ?: false }

    override fun httpClient() = HttpClient(OkHttp)

    override fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("JagaNet", text))
    }

    override fun openUrl(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        @Volatile private var instance: AndroidPlatform? = null
        /** One per process, so the tunnel outlives activity recreation. */
        fun get(context: Context, apiUrl: String) = instance ?: synchronized(this) {
            instance ?: AndroidPlatform(context.applicationContext, apiUrl).also { instance = it }
        }
    }
}
