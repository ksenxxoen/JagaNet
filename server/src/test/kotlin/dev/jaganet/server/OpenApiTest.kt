package dev.jaganet.server

import dev.jaganet.api.Protocols
import dev.jaganet.server.http.OpenApi
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.application.plugin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * resources/openapi.yaml (issue #119) against the server: the same /v1 routes both ways, the same
 * models (every component with x-kotlin), and real answers of the key endpoints follow the schemas.
 */
class OpenApiTest {
    private val spec = OpenApi.document
    private val paths = spec["paths"]!!.jsonObject
    private val schemas = spec["components"]!!.jsonObject["schemas"]!!.jsonObject
    private val validator = SchemaValidator(spec)

    /** "GET /v1/auth/app/{id}" for every route of the running server under /v1 (and /health). */
    private fun routes(root: RoutingNode): Set<String> {
        val out = sortedSetOf<String>()
        fun walk(n: RoutingNode, path: String) {
            val p = when (val sel = n.selector) {
                is PathSegmentConstantRouteSelector -> "$path/${sel.value}"
                is PathSegmentParameterRouteSelector -> "$path/{${sel.name}}"
                else -> path
            }
            (n.selector as? HttpMethodRouteSelector)?.let { out += "${it.method.value} ${p.ifEmpty { "/" }}" }
            n.children.forEach { walk(it, p) }
        }
        walk(root, "")
        return out
    }

    private fun specRoutes(): Set<String> = paths.flatMap { (p, ops) -> ops.jsonObject.keys.filter { it in METHODS }.map { "${it.uppercase()} $p" } }.toSortedSet()

    @Test fun `every v1 route is in openapi yaml and every documented route exists`() = harness {
        var root: RoutingRoot? = null
        b.application { root = plugin(RoutingRoot) }
        b.startApplication()
        val server = routes(root!!).filter { it.substringAfter(' ').let { p -> p == "/v1" || p.startsWith("/v1/") } }.toSortedSet()
        val documented = specRoutes()
        val missing = server - documented
        val extra = documented.filter { it.substringAfter(' ').startsWith("/v1/") } - server
        assertTrue(missing.isEmpty(), "Routes without a description in openapi.yaml:\n" + missing.joinToString("\n"))
        assertTrue(extra.isEmpty(), "openapi.yaml describes routes the server does not have:\n" + extra.joinToString("\n"))
        // Outside /v1 only /health is described, and it is there.
        assertTrue("GET /health" in routes(root!!))
        assertEquals(setOf("GET /health"), documented.filterNot { it.substringAfter(' ').startsWith("/v1/") }.toSet())
    }

    @Test fun `the conformance check notices a route without a description`() {
        val documented = specRoutes()
        assertTrue("GET /v1/me" in documented)
        assertTrue("GET /v1/me/new-thing" !in documented)
    }

    @Test fun `every component matches its Kotlin class`() {
        // Classes reachable from a request body are described as requests (fields with defaults may be left out).
        val request = mutableSetOf<String>()
        fun collect(e: JsonElement) {
            when (e) {
                is JsonObject -> {
                    (e["\$ref"] as? JsonPrimitive)?.content?.removePrefix("#/components/schemas/")?.let { name ->
                        if (request.add(name)) schemas[name]?.let(::collect)
                    }
                    e.values.forEach(::collect)
                }
                is kotlinx.serialization.json.JsonArray -> e.forEach(::collect)
                else -> {}
            }
        }
        paths.values.forEach { ops -> ops.jsonObject.values.forEach { op -> (op as? JsonObject)?.get("requestBody")?.let(::collect) } }

        val problems = mutableListOf<String>()
        for ((name, schema) in schemas) {
            val cls = (schema.jsonObject["x-kotlin"] as? JsonPrimitive)?.content ?: continue
            val d = runCatching { OpenApiSchemas.descriptorOf(cls) }.getOrElse { problems += "$name: no class $cls"; continue }
            if (OpenApiSchemas.componentName(d.serialName) != name) problems += "$name: the class $cls is named ${OpenApiSchemas.componentName(d.serialName)}"
            val mode = when {
                schema.jsonObject["x-explicit-nulls"]?.jsonPrimitive?.booleanOrNull == true -> OpenApiSchemas.Mode.EXPLICIT_NULLS
                name in request -> OpenApiSchemas.Mode.REQUEST
                else -> OpenApiSchemas.Mode.RESPONSE
            }
            val expected = OpenApiSchemas.component(d, mode)
            if (OpenApiSchemas.shape(schema) != expected) problems += "$name ($mode) differs from $cls. Expected:\n    $expected\n  openapi.yaml:\n    ${OpenApiSchemas.shape(schema)}"
            // Everything it refers to is described too.
            OpenApiSchemas.closure(d).keys.filter { it !in schemas }.forEach { problems += "$name refers to $it, which is not in components.schemas" }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test fun `every ref resolves and every operation has an id, a tag and an answer`() {
        val refs = mutableListOf<String>()
        fun collect(e: JsonElement) {
            when (e) {
                is JsonObject -> { (e["\$ref"] as? JsonPrimitive)?.let { refs += it.content }; e.values.forEach(::collect) }
                is kotlinx.serialization.json.JsonArray -> e.forEach(::collect)
                else -> {}
            }
        }
        collect(spec)
        refs.distinct().forEach { r -> runCatching { validator.resolve(r) }.onFailure { fail("Unresolved $r") } }
        val ids = mutableSetOf<String>()
        for ((p, ops) in paths) for ((m, op) in ops.jsonObject) {
            if (m !in METHODS) continue
            val o = op.jsonObject
            val id = o["operationId"]?.jsonPrimitive?.content ?: fail("$m $p: no operationId")
            assertTrue(ids.add(id), "Duplicate operationId $id")
            assertTrue(o["tags"]!!.jsonArray.isNotEmpty(), "$m $p: no tag")
            assertTrue(o["responses"]!!.jsonObject.containsKey("200"), "$m $p: no 200 answer")
        }
        assertEquals("3.1.0", spec["openapi"]!!.jsonPrimitive.content)
    }

    /** Calls [url] and checks the answer against the schema openapi.yaml gives [specPath] for [method]. */
    private suspend fun Harness.answers(method: HttpMethod, specPath: String, url: String = specPath, token: String? = null, body: String? = null, status: HttpStatusCode = HttpStatusCode.OK): JsonElement {
        val res = b.createClient {}.request(url) {
            this.method = method
            token?.let { header("Authorization", "Bearer $it") }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        val text = res.bodyAsText()
        assertEquals(status, res.status, "${method.value} $url: $text")
        val op = paths[specPath]?.jsonObject?.get(method.value.lowercase())?.jsonObject ?: fail("${method.value} $specPath is not in openapi.yaml")
        val answer = if (status == HttpStatusCode.OK) op["responses"]!!.jsonObject["200"]!! else spec["components"]!!.jsonObject["responses"]!!.jsonObject["Error"]!!
        val schema = answer.jsonObject["content"]!!.jsonObject["application/json"]!!.jsonObject["schema"]!!
        val value = Json.parseToJsonElement(text)
        val errors = validator.validate(value, schema)
        assertTrue(errors.isEmpty(), "${method.value} $url does not follow openapi.yaml:\n" + errors.joinToString("\n") + "\n" + text)
        return value
    }

    @Test fun `answers of the key endpoints follow the schemas`() = harness {
        val get = HttpMethod.Get
        val post = HttpMethod.Post
        answers(get, "/health")
        answers(get, "/v1/site")
        answers(get, "/v1/i18n/{lang}", "/v1/i18n/ru")
        answers(get, "/v1/billing/plans")
        answers(get, "/v1/support")
        answers(get, "/v1/auth/telegram/config")
        // Errors have one shape.
        answers(get, "/v1/me", status = HttpStatusCode.Unauthorized)

        // Sign-in by e-mail code, as the website does it.
        answers(post, "/v1/auth/email/start", body = """{"email":"user@test.dev"}""")
        val session = answers(post, "/v1/auth/email/verify", body = """{"email":"user@test.dev","code":"${mail["user@test.dev"]}","device":{"name":"Website","platform":"other"}}""")
        val user = session.jsonObject["token"]!!.jsonPrimitive.content
        val owner = signIn("owner@test.dev").session.token

        // Account.
        answers(get, "/v1/me", token = user)
        answers(get, "/v1/me", token = owner)
        answers(get, "/v1/me/payments", token = user)
        answers(get, "/v1/keys", "/v1/keys?supportedProtocols=vless", token = user)
        answers(post, "/v1/keys", token = user)
        answers(get, "/v1/devices", "/v1/devices?supportedProtocols=amneziawg,vless", token = user)
        answers(get, "/v1/servers", "/v1/servers?supportedProtocols=amneziawg,vless", token = user)
        answers(get, "/v1/telegram", token = user)
        answers(get, "/v1/referrals/invite", token = user)
        answers(get, "/v1/referrals/stats", token = user)
        val order = answers(post, "/v1/orders", token = user, body = """{"tariffId":"${tariff(MONTH)}"}""")
        answers(get, "/v1/orders/{id}", "/v1/orders/${order.jsonObject["id"]!!.jsonPrimitive.content}", token = user)

        // Owner: server status, nodes and the admin panel.
        val node = services.nodes.create(dev.jaganet.api.NodeReq("Edge", "Amsterdam", "NL", maxPeers = 100))
        val nodeToken = node.command.substringAfter("NODE_TOKEN=").substringBefore(' ')
        services.nodes.register(nodeToken, dev.jaganet.server.services.NodeRegisterReq("203.0.113.7", 51821, "amneziawg"))
        answers(get, "/v1/admin/servers/overview", token = owner)
        answers(get, "/v1/admin/monitor", token = owner)
        answers(get, "/v1/admin/nodes", token = owner)
        answers(get, "/v1/admin/overview", token = owner)
        answers(get, "/v1/admin/settings", token = owner)
        answers(get, "/v1/admin/users", token = owner)
        answers(get, "/v1/admin/control/dashboard", token = owner)
        answers(get, "/v1/admin/payments", token = owner)
        answers(get, "/v1/admin/analytics", token = owner)
        answers(get, "/v1/admin/tariffs", token = owner)
        answers(get, "/v1/admin/referrals", token = owner)
        answers(get, "/v1/admin/referral-rewards", token = owner)
    }

    @Test fun `openapi json is for the owner only`() = harness {
        val c = b.createClient {}
        suspend fun status(token: String?) = c.request("/v1/openapi.json") { token?.let { header("Authorization", "Bearer $it") } }
        assertEquals(HttpStatusCode.NotFound, status(null).status)
        assertEquals(HttpStatusCode.NotFound, status("not-a-token").status)
        assertEquals(HttpStatusCode.NotFound, status(signIn("user@test.dev").session.token).status)
        val res = status(signIn("owner@test.dev").session.token)
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue("application/json" in res.headers["Content-Type"]!!)
        assertEquals("no-store", res.headers["Cache-Control"])
        val doc = Protocols.json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("3.1.0", doc["openapi"]!!.jsonPrimitive.content)
        assertEquals(paths.keys, doc["paths"]!!.jsonObject.keys)
    }

    private companion object {
        val METHODS = setOf("get", "post", "put", "patch", "delete")
    }
}
