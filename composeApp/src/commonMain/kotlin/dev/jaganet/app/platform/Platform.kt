package dev.jaganet.app.platform

import dev.jaganet.api.Platform as ApiPlatform
import dev.jaganet.app.tunnel.TunnelEngine
import io.ktor.client.HttpClient

/** Small key/value store for the session token and settings (Keychain / Keystore-backed on devices). */
interface SecureStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

/** Everything platform-specific the shared app needs. Built in MainActivity, MainViewController, desktop main. */
interface AppPlatform {
    val kind: ApiPlatform
    val deviceName: String
    val apiUrl: String
    val store: SecureStore
    val tunnel: TunnelEngine
    fun httpClient(): HttpClient
    /** Opens the share sheet / copies a link. */
    fun share(text: String)
    fun copy(text: String)
    fun openUrl(url: String)
}

class MemoryStore : SecureStore {
    private val m = mutableMapOf<String, String>()
    override fun get(key: String) = m[key]
    override fun set(key: String, value: String) { m[key] = value }
    override fun remove(key: String) { m.remove(key) }
}
