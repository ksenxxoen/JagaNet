package dev.jaganet.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test

/**
 * Not a check: a helper for writing openapi.yaml. With OPENAPI_DUMP=<file> (lines "request|response|explicit
 * <class>") it writes the component schemas of those classes and of everything they refer to, as JSON,
 * to OPENAPI_OUT (default build/openapi-components.json). Paste what you need into openapi.yaml.
 *   OPENAPI_DUMP=classes.txt ./gradlew :server:test --tests '*OpenApiDumpTest*'
 */
class OpenApiDumpTest {
    @Test fun dump() {
        val input = System.getenv("OPENAPI_DUMP") ?: return
        val roots = File(input).readLines().filter { it.isNotBlank() }.map { it.trim().split(' ', limit = 2).let { (m, c) -> m to c } }
        val request = linkedMapOf<String, kotlinx.serialization.descriptors.SerialDescriptor>()
        val all = linkedMapOf<String, kotlinx.serialization.descriptors.SerialDescriptor>()
        val explicit = mutableSetOf<String>()
        for ((mode, cls) in roots) {
            val d = OpenApiSchemas.descriptorOf(cls)
            if (mode == "request") OpenApiSchemas.closure(d, request)
            if (mode == "explicit") explicit += OpenApiSchemas.componentName(d.serialName)
            OpenApiSchemas.closure(d, all)
        }
        val out = JsonObject(all.toSortedMap().mapValues { (name, d) ->
            val mode = when {
                name in explicit -> OpenApiSchemas.Mode.EXPLICIT_NULLS
                name in request -> OpenApiSchemas.Mode.REQUEST
                else -> OpenApiSchemas.Mode.RESPONSE
            }
            val schema = OpenApiSchemas.component(d, mode)
            JsonObject(schema + mapOf("x-kotlin" to JsonPrimitive(OpenApiSchemas.jvmName(d))) +
                (if (mode == OpenApiSchemas.Mode.EXPLICIT_NULLS) mapOf("x-explicit-nulls" to JsonPrimitive(true)) else emptyMap()))
        })
        File(System.getenv("OPENAPI_OUT") ?: "build/openapi-components.json").writeText(out.toString())
    }
}
