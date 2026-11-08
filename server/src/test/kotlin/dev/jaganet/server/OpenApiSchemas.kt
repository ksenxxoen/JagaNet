package dev.jaganet.server

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.serializer

/**
 * The JSON Schema (OpenAPI 3.1) of a @Serializable class, as the API's Json (Protocols.json:
 * encodeDefaults, no explicit nulls) writes and reads it. OpenApiTest compares every component of
 * openapi.yaml that names its class (`x-kotlin`) with this, so the file cannot drift from the models.
 *
 * `required` depends on the direction:
 *  - responses: every non-null field is always sent (defaults are encoded), null fields are left out;
 *  - requests (the class is reachable from a request body): a field with a default may be left out;
 *  - `x-explicit-nulls: true` (a response sent with its nulls, see respondKeepingNulls): nullable
 *    fields are always there, as `null` when empty.
 * Nullable fields of request classes accept `null`.
 */
@OptIn(ExperimentalSerializationApi::class)
object OpenApiSchemas {
    enum class Mode { RESPONSE, REQUEST, EXPLICIT_NULLS }

    /** Component name of a class: its name without the package, nested names joined (Vless.NodeSettings → VlessNodeSettings). */
    fun componentName(serialName: String): String =
        serialName.removeSuffix("?").split('.').dropWhile { it.firstOrNull()?.isLowerCase() == true }.joinToString("")

    fun descriptorOf(jvmClass: String): SerialDescriptor = serializer(Class.forName(jvmClass)).descriptor

    /** The JVM class name of a descriptor's class (nested classes with `$`). */
    fun jvmName(d: SerialDescriptor): String {
        val parts = d.serialName.removeSuffix("?").split('.')
        for (pkg in parts.indices.reversed()) {
            val name = parts.take(pkg).joinToString(".") + (if (pkg > 0) "." else "") + parts.drop(pkg).joinToString("$")
            if (runCatching { Class.forName(name) }.isSuccess) return name
        }
        error("No class for ${d.serialName}")
    }

    private fun isNamed(d: SerialDescriptor) = d.kind == StructureKind.CLASS || d.kind == SerialKind.ENUM || d.kind == StructureKind.OBJECT

    private fun isJson(d: SerialDescriptor) = d.serialName.removeSuffix("?").startsWith("kotlinx.serialization.json.")

    /** The component schema of a class or enum. */
    fun component(d: SerialDescriptor, mode: Mode): JsonObject {
        val out = linkedMapOf<String, JsonElement>()
        when (d.kind) {
            SerialKind.ENUM -> {
                out["type"] = JsonPrimitive("string")
                out["enum"] = JsonArray((0 until d.elementsCount).map { JsonPrimitive(d.getElementName(it)) })
            }
            StructureKind.CLASS, StructureKind.OBJECT -> {
                out["type"] = JsonPrimitive("object")
                val props = linkedMapOf<String, JsonElement>()
                val required = mutableListOf<String>()
                for (i in 0 until d.elementsCount) {
                    val e = d.getElementDescriptor(i)
                    val name = d.getElementName(i)
                    val nullable = e.isNullable
                    val hasDefault = d.isElementOptional(i)
                    val withNull = nullable && mode != Mode.RESPONSE
                    props[name] = ref(e, withNull)
                    val isRequired = when (mode) {
                        Mode.RESPONSE -> !nullable
                        Mode.REQUEST -> !nullable && !hasDefault
                        Mode.EXPLICIT_NULLS -> true
                    }
                    if (isRequired) required += name
                }
                if (props.isNotEmpty()) out["properties"] = JsonObject(props)
                if (required.isNotEmpty()) out["required"] = JsonArray(required.map(::JsonPrimitive))
            }
            else -> error("Not a class or enum: ${d.serialName} (${d.kind})")
        }
        return JsonObject(out)
    }

    /** The schema of a field: a $ref for classes and enums, inline otherwise. */
    fun ref(d: SerialDescriptor, withNull: Boolean = false): JsonObject {
        val base: JsonObject = when {
            d.isInline -> ref(d.getElementDescriptor(0))
            isJson(d) -> when (d.serialName.removeSuffix("?").substringAfterLast('.')) {
                "JsonObject" -> obj("type" to JsonPrimitive("object"))
                "JsonArray" -> obj("type" to JsonPrimitive("array"))
                else -> obj()
            }
            isNamed(d) -> obj("\$ref" to JsonPrimitive("#/components/schemas/" + componentName(d.serialName)))
            d.kind == StructureKind.LIST -> obj("type" to JsonPrimitive("array"), "items" to ref(d.getElementDescriptor(0), d.getElementDescriptor(0).isNullable))
            d.kind == StructureKind.MAP -> obj("type" to JsonPrimitive("object"), "additionalProperties" to ref(d.getElementDescriptor(1), d.getElementDescriptor(1).isNullable))
            d.kind is PrimitiveKind -> primitive(d.kind as PrimitiveKind)
            d.kind is PolymorphicKind -> error("Polymorphic ${d.serialName} is not described yet")
            else -> error("Unsupported ${d.serialName} (${d.kind})")
        }
        if (!withNull) return base
        val type = base["type"]
        return if (type is JsonPrimitive) JsonObject(base + ("type" to JsonArray(listOf(type, JsonPrimitive("null")))))
        else obj("anyOf" to JsonArray(listOf(base, obj("type" to JsonPrimitive("null")))))
    }

    private fun primitive(k: PrimitiveKind): JsonObject = when (k) {
        PrimitiveKind.STRING, PrimitiveKind.CHAR -> obj("type" to JsonPrimitive("string"))
        PrimitiveKind.BOOLEAN -> obj("type" to JsonPrimitive("boolean"))
        PrimitiveKind.INT, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> obj("type" to JsonPrimitive("integer"), "format" to JsonPrimitive("int32"))
        PrimitiveKind.LONG -> obj("type" to JsonPrimitive("integer"), "format" to JsonPrimitive("int64"))
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> obj("type" to JsonPrimitive("number"))
    }

    private fun obj(vararg p: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*p))

    /** Every class and enum a class refers to, itself included. */
    fun closure(d: SerialDescriptor, into: MutableMap<String, SerialDescriptor> = linkedMapOf()): Map<String, SerialDescriptor> {
        fun walk(x: SerialDescriptor) {
            when {
                x.isInline -> walk(x.getElementDescriptor(0))
                isJson(x) -> {}
                isNamed(x) -> {
                    val name = componentName(x.serialName)
                    into[name]?.let { seen ->
                        check(seen.serialName.removeSuffix("?") == x.serialName.removeSuffix("?")) { "Two classes named $name: ${seen.serialName} and ${x.serialName}" }
                        return
                    }
                    into[name] = x
                    if (x.kind != SerialKind.ENUM) for (i in 0 until x.elementsCount) walk(x.getElementDescriptor(i))
                }
                x.kind == StructureKind.LIST -> walk(x.getElementDescriptor(0))
                x.kind == StructureKind.MAP -> walk(x.getElementDescriptor(1))
            }
        }
        walk(d)
        return into
    }

    /** Drops what documents a schema but does not describe the data (descriptions, examples, x-*). */
    fun shape(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterKeys { it != "description" && it != "example" && it != "examples" && it != "title" && !it.startsWith("x-") }.mapValues { shape(it.value) })
        is JsonArray -> JsonArray(e.map(::shape))
        else -> e
    }
}

/**
 * A small JSON Schema validator for the subset openapi.yaml uses: \$ref (local), type, properties,
 * required, items, additionalProperties, enum, anyOf/oneOf, const. Strict: an object property the
 * schema does not list is an error unless additionalProperties allows it, so an undocumented field
 * in a response fails the test.
 */
class SchemaValidator(private val root: JsonObject) {
    fun validate(value: JsonElement, schema: JsonElement, path: String = "$"): List<String> {
        if (schema !is JsonObject) return emptyList()
        schema["\$ref"]?.let { r -> return validate(value, resolve(r.jsonPrimitive.content), path) }
        val errors = mutableListOf<String>()
        schema["anyOf"]?.let { list ->
            if (list.jsonArray.none { validate(value, it, path).isEmpty() }) errors += "$path: matches none of anyOf"
        }
        schema["oneOf"]?.let { list ->
            if (list.jsonArray.count { validate(value, it, path).isEmpty() } != 1) errors += "$path: does not match exactly one of oneOf"
        }
        schema["const"]?.let { if (it != value) errors += "$path: expected $it" }
        schema["enum"]?.let { if (value !in it.jsonArray) errors += "$path: $value is not one of ${it.jsonArray}" }
        schema["type"]?.let { t ->
            val types = if (t is JsonArray) t.map { it.jsonPrimitive.content } else listOf(t.jsonPrimitive.content)
            if (types.none { typeMatches(value, it) }) return errors + "$path: $value is not ${types.joinToString("|")}"
        }
        when (value) {
            is JsonObject -> {
                val props = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
                schema["required"]?.jsonArray?.forEach { r ->
                    if (r.jsonPrimitive.content !in value) errors += "$path: missing required ${r.jsonPrimitive.content}"
                }
                val extra = schema["additionalProperties"]
                for ((k, v) in value) {
                    val p = props[k]
                    when {
                        p != null -> errors += validate(v, p, "$path.$k")
                        extra is JsonObject -> errors += validate(v, extra, "$path.$k")
                        extra is JsonPrimitive && extra.booleanOrNull == true -> {}
                        schema["properties"] != null || extra != null -> errors += "$path: undocumented field $k"
                    }
                }
            }
            is JsonArray -> schema["items"]?.let { items -> value.forEachIndexed { i, v -> errors += validate(v, items, "$path[$i]") } }
            else -> {}
        }
        return errors
    }

    private fun typeMatches(v: JsonElement, type: String): Boolean = when (type) {
        "null" -> v is JsonNull
        "object" -> v is JsonObject
        "array" -> v is JsonArray
        "string" -> v is JsonPrimitive && v !is JsonNull && v.isString
        "boolean" -> v is JsonPrimitive && !v.isString && v.booleanOrNull != null
        "integer" -> v is JsonPrimitive && !v.isString && v.longOrNull != null
        "number" -> v is JsonPrimitive && !v.isString && v.doubleOrNull != null
        else -> false
    }

    fun resolve(ref: String): JsonElement {
        require(ref.startsWith("#/")) { "Only local refs: $ref" }
        return ref.removePrefix("#/").split('/').fold(root as JsonElement) { e, k -> e.jsonObject[k] ?: error("Unresolved $ref") }
    }

    companion object {
        fun str(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull
    }
}
