package dev.jaganet.app.state

import dev.jaganet.app.platform.SecureStore
import dev.jaganet.app.tunnel.SplitTunnel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Device-local preferences. Never sent to the server. */
@Serializable
data class Settings(
    val killSwitch: Boolean = true,
    val autoConnect: Boolean = false,
    val notifications: Boolean = true,
    val startOnBoot: Boolean = false,
    /** null = automatic: the best protocol both the server and this device support. */
    val protocol: String? = null,
    val serverId: String? = null,
    val split: SplitTunnel = SplitTunnel(),
)

class SettingsStore(private val store: SecureStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(store.get(KEY)?.let { runCatching { json.decodeFromString<Settings>(it) }.getOrNull() } ?: Settings())
    val state: StateFlow<Settings> = _state

    fun update(f: (Settings) -> Settings) {
        _state.value = f(_state.value)
        store.set(KEY, json.encodeToString(Settings.serializer(), _state.value))
    }

    private companion object { const val KEY = "jaganet.settings" }
}
