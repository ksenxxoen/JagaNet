package dev.jaganet.app

/**
 * Implemented in Swift (iosApp/iosApp/NativeBridge.swift) and handed to
 * [MainViewController]. Callback-based so Swift needs no coroutines; JSON strings
 * keep the bridge stable when the Kotlin models change.
 */
interface IosNativeBridge {
    val deviceName: String

    // Keychain
    fun keychainGet(key: String): String?
    fun keychainSet(key: String, value: String)
    fun keychainRemove(key: String)

    // Tunnel (NETunnelProviderManager + the Packet Tunnel extension)
    fun protocols(): List<String>
    fun requestPermission(done: (Boolean) -> Unit)
    /** done(clientParamsJson, error) */
    fun clientParams(protocolId: String, done: (String?, String?) -> Unit)
    /** done(error) */
    fun start(configJson: String, optionsJson: String, done: (String?) -> Unit)
    fun stop(done: () -> Unit)
    /** done(EngineStatus JSON) */
    fun status(done: (String) -> Unit)
    fun setListener(onStatus: (String) -> Unit, onLog: (String) -> Unit)

    // UI helpers
    fun share(text: String)
    fun copy(text: String)
    fun openUrl(url: String)
}
