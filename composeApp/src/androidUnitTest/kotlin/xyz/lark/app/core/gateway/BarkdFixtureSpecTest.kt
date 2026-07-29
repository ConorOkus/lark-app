package xyz.lark.app.core.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Plan R14's circularity-breaker: [BarkdFixtures] drive the [BarkdApi] decode tests, so this
 * JVM-only test pins the fixtures themselves to the vendored contract. Every JSON key a
 * fixture uses (except the schemaless `metadata` subtree) must appear verbatim in
 * docs/gateway/barkd-openapi-0.4.0.json, so fixture drift fails the build.
 */
class BarkdFixtureSpecTest {

    private val specText: String by lazy { locateSpec().readText() }

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
    fun everyFixtureFieldNameAppearsInTheVendoredSpec() {
        BarkdFixtures.byPath.forEach { (path, fixture) ->
            val keys = mutableSetOf<String>()
            collectKeys(Json.parseToJsonElement(fixture), keys)
            assertTrue(keys.isNotEmpty(), "fixture for $path has no keys to validate")
            keys.forEach { key ->
                assertTrue(
                    specText.contains("\"$key\""),
                    "fixture for $path uses key \"$key\" that is not in the vendored 0.4.0 spec",
                )
            }
        }
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

    /** Collects object keys recursively, skipping the schemaless `metadata` subtree. */
    private fun collectKeys(element: JsonElement, into: MutableSet<String>) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                into += key
                if (key != "metadata") collectKeys(value, into)
            }
            is JsonArray -> element.forEach { collectKeys(it, into) }
            else -> Unit
        }
    }
}
