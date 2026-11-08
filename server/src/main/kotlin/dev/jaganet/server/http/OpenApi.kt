package dev.jaganet.server.http

import dev.jaganet.api.Role
import dev.jaganet.server.notFound
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.withCharset
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * The OpenAPI 3.1 description of /v1: the hand-kept file resources/openapi.yaml (docs/WEB-FRONTEND.md,
 * "API description"). OpenApiTest keeps it in step with the routes and the @Serializable models.
 */
object OpenApi {
    val yaml: String by lazy { checkNotNull(javaClass.classLoader.getResource("openapi.yaml")) { "openapi.yaml is missing" }.readText() }

    /** The same document as JSON (for /v1/openapi.json and the tests). */
    val document: JsonObject by lazy { toJson(Load(LoadSettings.builder().build()).loadFromString(yaml)) as JsonObject }
    private val text: String by lazy { document.toString() }

    fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
        is List<*> -> JsonArray(v.map(::toJson))
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    fun Route.openApiDocument(s: Services) {
        // It lists every admin endpoint, so only the owner gets it; anyone else (signed in or not) sees 404.
        get("/openapi.json") {
            val p = call.request.headers[HttpHeaders.Authorization]?.let { h -> runCatching { s.auth.authenticate(h) }.getOrNull() }
            if (p?.user?.role != Role.OWNER) throw notFound()
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(text, ContentType.Application.Json.withCharset(Charsets.UTF_8))
        }
    }
}
