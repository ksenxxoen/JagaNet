package dev.jaganet.android

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import dev.jaganet.app.AndroidPlatform
import dev.jaganet.app.App
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {
    private var pendingConsent: CompletableDeferred<Boolean>? = null
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pendingConsent?.complete(it.resultCode == Activity.RESULT_OK)
        pendingConsent = null
    }

    /** Shows Android's "Connection request" dialog the first time. */
    private suspend fun requestVpnConsent(): Boolean {
        val intent = VpnService.prepare(this) ?: return true
        val d = CompletableDeferred<Boolean>().also { pendingConsent = it }
        consent.launch(intent)
        return d.await()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val platform = AndroidPlatform.get(this, BuildConfig.API_URL, Backends::create)
        platform.vpnConsent = ::requestVpnConsent
        setContent { App(platform) }
    }

    override fun onDestroy() {
        AndroidPlatform.get(this, BuildConfig.API_URL, Backends::create).vpnConsent = null
        super.onDestroy()
    }
}
