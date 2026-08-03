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
 * JVM-only test pins the fixtures themselves to their vendored contract — stock 0.4.0
 * (docs/gateway/barkd-openapi-0.4.0.json) for [BarkdFixtures.byPath] and the fork
 * 0.1.0-beta.6 (docs/gateway/barkd-fork-openapi-0.1.0-beta.6.json) for
 * [BarkdFixtures.forkByPath]. Each fixture is validated structurally against the response
 * schema of ITS OWN endpoint in ITS OWN spec — the spec's `paths` entry resolved through the
 * `$ref`/`allOf`/`oneOf` composition into `components.schemas` — so a key that only exists
 * on some other endpoint's schema fails, not just a key missing from the whole document.
 * Schema-required properties must be present too, so fixtures stay spec-valid responses.
 */
class BarkdFixtureSpecTest {

    private companion object {
        const val LDK_PAYMENT_PATH = "/api/v1/lightning/channels/ldk-payment/{hash}"

        /** Every status the settlement mapping reads; `sent`/`claimed` terminal, `failed` fatal. */
        val LDK_STATUSES = listOf("pending", "claimable", "sent", "claimed", "failed")
    }

    private val stockSpec by lazy {
        SpecValidator(
            specFile = locateSpec("barkd-openapi-0.4.0.json"),
            label = "0.4.0",
            postPaths = setOf(
                "/api/v1/wallet/bip321",
                "/api/v1/wallet/send",
                "/api/v1/wallet/refresh/all",
                "/api/v1/wallet/create",
            ),
        )
    }

    private val forkSpec by lazy {
        SpecValidator(
            specFile = locateSpec("barkd-fork-openapi-0.1.0-beta.6.json"),
            label = "fork 0.1.0-beta.6",
            postPaths = setOf(
                "/api/v1/wallet/create",
                "/api/v1/wallet/addresses/next",
                "/api/v1/wallet/send",
                "/api/v1/wallet/refresh/all",
                "/api/v1/lightning/channels/ldk-pay",
                "/api/v1/lightning/channels/ldk-invoice",
            ),
        )
    }

    private fun locateSpec(fileName: String): File {
        val startDir = System.getProperty("user.dir") ?: "."
        var dir: File? = File(startDir).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/gateway/$fileName")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        fail("vendored spec $fileName not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun everyFixtureMatchesItsOwnEndpointResponseSchema() {
        BarkdFixtures.byPath.forEach { (path, fixture) ->
            stockSpec.validateFixture(path, fixture)
        }
    }

    @Test
    fun everyForkFixtureMatchesItsOwnForkEndpointResponseSchema() {
        BarkdFixtures.forkByPath.forEach { (path, fixture) ->
            forkSpec.validateFixture(path, fixture)
        }
    }

    /**
     * `ldk-payment/{hash}` is hash-scoped, so it has no [BarkdFixtures.forkByPath] entry — but
     * the settlement loop reads it every poll, so its shape is pinned against the templated
     * spec path directly.
     */
    @Test
    fun theSettlementPollFixtureMatchesItsForkEndpointResponseSchema() {
        forkSpec.validateFixture(LDK_PAYMENT_PATH, BarkdFixtures.FORK_LDK_PAYMENT)
        LDK_STATUSES.forEach { status ->
            forkSpec.validateFixture(LDK_PAYMENT_PATH, BarkdFixtures.forkLdkPayment(status))
        }
    }

    /**
     * The send path keys its double-pay guard on this exact body, so drift in the error shape is
     * a correctness problem, not cosmetics: pin it against the fork's own 500 schema.
     */
    @Test
    fun theLdkNotInitializedBodyMatchesTheForkErrorSchema() {
        forkSpec.validateErrorFixture(
            "/api/v1/lightning/channels/ldk-pay",
            "500",
            BarkdFixtures.FORK_LDK_NOT_INITIALIZED,
        )
    }

    /**
     * Every status the settlement mapping branches on must be one the fork documents. The fork
     * types `status` as a bare string and documents the values in its description rather than as
     * an enum, so this reads that description out of the schema instead of grepping the file —
     * which would match the same words anywhere in the document.
     */
    @Test
    fun everyLdkStatusTheAppBranchesOnIsDocumentedByTheForkSpec() {
        val documented = forkSpec.propertyDescription("LdkPaymentInfo", "status")
        LDK_STATUSES.forEach { status ->
            assertTrue(
                status in documented,
                "LDK status \"$status\" is not documented by the fork's LdkPaymentInfo.status: $documented",
            )
        }
    }

    /** Every LDK route the app calls is covered by a spec-validated fixture. */
    @Test
    fun everyLdkRouteTheAppCallsHasASpecValidatedFixture() {
        val covered = BarkdFixtures.forkByPath.keys + LDK_PAYMENT_PATH
        listOf(
            "/api/v1/lightning/channels",
            "/api/v1/lightning/channels/ldk-pay",
            "/api/v1/lightning/channels/ldk-invoice",
            "/api/v1/lightning/channels/ldk-payments",
            LDK_PAYMENT_PATH,
        ).forEach { path ->
            assertTrue(path in covered, "no spec-validated fixture covers $path")
        }
    }

    /** The reason this validator is endpoint-scoped: a key that is real elsewhere still fails here. */
    @Test
    fun aKeyMovedToTheWrongEndpointFailsValidation() {
        val drifted = BarkdFixtures.BALANCE.trimEnd().removeSuffix("}") + """, "tip_height": 1}"""
        val failure = assertFailsWith<AssertionError> {
            stockSpec.validateFixture("/api/v1/wallet/balance", drifted)
        }
        assertTrue("tip_height" in failure.message.orEmpty(), "failure should name the drifted key")
    }

    @Test
    fun everyNotificationDiscriminatorAppearsInTheVendoredSpec() {
        BarkdFixtures.NOTIFICATION_TYPES.forEach { type ->
            assertTrue(
                stockSpec.specText.contains("\"$type\""),
                "notification type \"$type\" is not in the vendored 0.4.0 spec",
            )
        }
    }

    /**
     * Schema-scoped structural validation against one vendored spec; [postPaths] are the
     * fixture paths [BarkdApi] requests with POST (every path not listed is a GET), and
     * [label] names the spec in failure messages.
     */
    private class SpecValidator(
        specFile: File,
        private val label: String,
        private val postPaths: Set<String>,
    ) {

        val specText: String = specFile.readText()
        private val spec: JsonObject = Json.parseToJsonElement(specText).jsonObject
        private val componentSchemas: JsonObject =
            spec.getValue("components").jsonObject.getValue("schemas").jsonObject

        /** Validates [fixture] against the success response schema of [path] in this spec. */
        fun validateFixture(path: String, fixture: String) {
            validate(Json.parseToJsonElement(fixture), responseSchema(path, "200"), "fixture for $path")
        }

        /**
         * Validates [fixture] against the [status] response schema of [path] — error bodies the
         * app reads decisions from must be pinned too, not just the happy paths.
         */
        fun validateErrorFixture(path: String, status: String, fixture: String) {
            validate(
                Json.parseToJsonElement(fixture),
                responseSchema(path, status),
                "$status fixture for $path",
            )
        }

        /**
         * The `description` of one property of a component schema. Some fork fields document
         * their allowed values in prose instead of an `enum`, and those values are still a
         * contract the app branches on.
         */
        fun propertyDescription(schemaName: String, property: String): String =
            componentSchemas.getValue(schemaName).jsonObject
                .getValue("properties").jsonObject
                .getValue(property).jsonObject
                .getValue("description").jsonPrimitive.content

        // --- Endpoint -> response schema ---

        private fun responseSchema(path: String, status: String): JsonObject {
            val method = if (path in postPaths) "post" else "get"
            val operation = spec.getValue("paths").jsonObject.getValue(path).jsonObject.getValue(method)
            return operation.jsonObject
                .getValue("responses").jsonObject
                .getValue(status).jsonObject
                .getValue("content").jsonObject
                .getValue("application/json").jsonObject
                .getValue("schema").jsonObject
        }

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
                    ?: fail("$where uses key \"$key\" that is not a property of this endpoint's $label schema")
                validate(value, property.jsonObject, "$where.$key")
            }
            requiredNames(schema).forEach { name ->
                assertTrue(name in element, "$where is missing \"$name\", required by this endpoint's $label schema")
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
            } ?: fail("$where matches no oneOf variant of this endpoint's $label schema (bad discriminator?)")
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
}
