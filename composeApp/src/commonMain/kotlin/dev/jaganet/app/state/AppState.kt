package dev.jaganet.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jaganet.api.ApiClient
import dev.jaganet.api.DeviceInfo
import dev.jaganet.api.SessionRes
import dev.jaganet.api.User
import dev.jaganet.api.i18n.Lang
import dev.jaganet.app.i18n.AppLang
import dev.jaganet.app.platform.AppPlatform
import dev.jaganet.app.tunnel.TunnelController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SavedSession(val token: String, val user: User, val deviceId: String)

/** App-wide state: session, API, settings, tunnel, navigation. One instance per app run. */
class AppState(val platform: AppPlatform) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true }

    private var session by mutableStateOf(platform.store.get(SESSION)?.let { runCatching { json.decodeFromString<SavedSession>(it) }.getOrNull() })
    val user: User? get() = session?.user
    val deviceId: String? get() = session?.deviceId

    /** Bumped after mutations so screens reload their data. */
    var dataVersion by mutableIntStateOf(0)
        private set
    fun invalidate() { dataVersion++ }

    val settings = SettingsStore(platform.store)
    init { AppLang.current = Lang.of(settings.state.value.language) ?: Lang.DEFAULT }
    val api = ApiClient(platform.apiUrl, platform.httpClient(), token = { session?.token }, onUnauthorized = { scope.launch { clearSession() } }, lang = { AppLang.current.code })

    fun setLanguage(l: Lang) {
        AppLang.current = l
        settings.update { it.copy(language = l.code) }
    }
    val router = Router(if (session == null) Route.SignIn else Route.Home)
    val tunnel = TunnelController(api, platform.tunnel, settings, scope, onChanged = ::invalidate)

    val thisDevice get() = DeviceInfo(platform.deviceName, platform.kind)

    fun signedIn(s: SessionRes) {
        session = SavedSession(s.token, s.user, s.deviceId)
        platform.store.set(SESSION, json.encodeToString(SavedSession.serializer(), session!!))
        router.reset(Route.Home)
    }

    fun signOut() = scope.launch {
        runCatching { if (tunnel.isUp) tunnel.disconnect() }
        runCatching { api.logout() }
        clearSession()
    }

    private fun clearSession() {
        session = null
        platform.store.remove(SESSION)
        router.reset(Route.SignIn)
    }

    private companion object { const val SESSION = "jaganet.session" }
}
