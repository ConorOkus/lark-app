package xyz.lark.app.core.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Plan R14's circularity-breaker: [BarkdFixtures] drive the [BarkdApi] decode tests, so this
 * JVM-only test pins the fixtures themselves to the vendored contract
 * (docs/gateway/barkd-openapi-0.4.0.json). Each fixture is validated structurally against
 * the response schema of ITS OWN endpoint — the spec's `paths` entry resolved through the
 * `$ref`/`allOf`/`oneOf` composition into `components.schemas` — so a key that only exists
 * on some other endpoint's schema fails, not just a key missing from the whole document.
 * Schema-required properties must be present too, so fixtures stay spec-valid responses.
 */
class BarkdFixtureSpecTest {

    private val specText: String by lazy { locateSpec().readText() }
    private val spec: JsonObject by lazy { Json.parseToJsonElement(specText).jsonObject }
    private val componentSchemas: JsonObject by lazy {
        spec.getValue("components").jsonObject.getValue("schemas").jsonObject
    }

    private fun locateSpec(): File {
        val startDir = System.getProperty("user.dir") ?: "."
        var dir: File? = File(startDir).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/gateway/barkd-openapi-0.4.0.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        fail("vendored spec not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun everyFixtureMatchesItsOwnEndpointResponseSchema() {
        BarkdFixtures.byPath.forEach { (path, fixture) ->
            validate(Json.parseToJsonElement(fixture), successResponseSchema(path), "fixture for $path")
        }
    }

    /** The reason this validator is endpoint-scoped: a key that is real elsewhere still fails here. */
    @Test
    fun aKeyMovedToTheWrongEndpointFailsValidation() {
        val drifted = BarkdFixtures.BALANCE.trimEnd().removeSuffix("}") + """, "tip_height": 1}"""
        val failure = assertFailsWith<AssertionError> {
            validate(
                Json.parseToJsonElement(drifted),
                successResponseSchema("/api/v1/wallet/balance"),
                "drifted balance fixture",
            )
        }
        assertTrue("tip_height" in failure.message.orEmpty(), "failure should name the drifted key")
    }

    @Test
    fun everyNotificationDiscriminatorAppearsInTheVendoredSpec() {
        BarkdFixtures.NOTIFICATION_TYPES.forEach { type ->
            assertTrue(
                specText.contains("\"$type\""),
                "notification type \"$type\" is not in the vendored 0.4.0 spec",
            )
        }
    }

    // --- Endpoint -> response schema ---

    /** The request method [BarkdApi] uses per fixture path; every path not listed is a GET. */
    private val postPaths = setOf(
        "/api/v1/wallet/bip321",
        "/api/v1/wallet/send",
        "/api/v1/wallet/refresh/all",
        "/api/v1/wallet/create",
    )

    private fun successResponseSchema(path: String): JsonObject {
        val method = if (path in postPaths) "post" else "get"
        val operation = spec.getValue("paths").jsonObject.getValue(path).jsonObject.getValue(method)
        return operation.jsonObject
            .getValue("responses").jsonObject
            .getValue("200").jsonObject
            .getValue("content").jsonObject
            .getValue("application/json").jsonObject
            .getValue("schema").jsonObject
    }

    // --- Schema-scoped structural validation ---

    /**
     * Walks [element] alongside [schema]: object keys must be properties of the schema at that
     * position and `required` properties must be present; arrays recurse through `items`.
     * Subtrees the spec leaves schemaless (Movement's `metadata`) are not descended into.
     */
    private fun validate(element: JsonElement, schema: JsonObject, where: String) {
        val resolved = resolve(schema)
        val variant = resolved["oneOf"]?.let { selectOneOfVariant(element, it.jsonArray, where) } ?: resolved
        when (element) {
            is JsonArray -> variant["items"]?.let { items ->
                element.forEachIndexed { index, item -> validate(item, items.jsonObject, "$where[$index]") }
            }
            is JsonObject -> validateObject(element, variant, where)
            else -> Unit // primitive/null: nothing structural to pin
        }
    }

    private fun validateObject(element: JsonObject, schema: JsonObject, where: String) {
        val properties = schema["properties"]?.jsonObject ?: return // schemaless subtree
        element.forEach { (key, value) ->
            val property = properties[key]
                ?: fail("$where uses key \"$key\" that is not a property of this endpoint's 0.4.0 schema")
            validate(value, property.jsonObject, "$where.$key")
        }
        requiredNames(schema).forEach { name ->
            assertTrue(name in element, "$where is missing \"$name\", required by this endpoint's 0.4.0 schema")
        }
    }

    /** Follows a `$ref` into `components.schemas` and flattens `allOf` composition (WalletVtxoInfo). */
    private fun resolve(schema: JsonObject): JsonObject {
        val direct = schema["\$ref"]?.let { lookUpRef(it) } ?: schema
        val branches = direct["allOf"]?.jsonArray ?: return direct
        val properties = mutableMapOf<String, JsonElement>()
        val required = mutableListOf<JsonElement>()
        branches.map { resolve(it.jsonObject) }.forEach { branch ->
            branch["properties"]?.jsonObject?.let(properties::putAll)
            branch["required"]?.jsonArray?.let(required::addAll)
        }
        return JsonObject(mapOf("properties" to JsonObject(properties), "required" to JsonArray(required)))
    }

    private fun lookUpRef(ref: JsonElement): JsonObject =
        componentSchemas.getValue(ref.jsonPrimitive.content.substringAfterLast('/')).jsonObject

    /**
     * Picks the `oneOf` variant whose enum-typed discriminator matches the fixture's value —
     * the notification union and VTXO/round state unions all discriminate on an enum string
     * property (`type` or `status`).
     */
    private fun selectOneOfVariant(element: JsonElement, variants: JsonArray, where: String): JsonObject {
        val fixture = element as? JsonObject ?: fail("$where must be an object to match a oneOf union")
        return variants.map { resolve(it.jsonObject) }.firstOrNull { variant ->
            val discriminators = enumProperties(variant)
            discriminators.isNotEmpty() &&
                discriminators.all { (key, allowed) -> (fixture[key] as? JsonPrimitive)?.content in allowed }
        } ?: fail("$where matches no oneOf variant of this endpoint's 0.4.0 schema (bad discriminator?)")
    }

    /** The variant's enum-constrained properties and their allowed wire values. */
    private fun enumProperties(variant: JsonObject): Map<String, Set<String>> {
        val properties = variant["properties"]?.jsonObject ?: return emptyMap()
        return buildMap {
            properties.forEach { (key, property) ->
                property.jsonObject["enum"]?.jsonArray
                    ?.let { allowed -> put(key, allowed.map { it.jsonPrimitive.content }.toSet()) }
            }
        }
    }

    private fun requiredNames(schema: JsonObject): List<String> =
        schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
}
