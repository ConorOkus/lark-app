package xyz.lark.app.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Words the user never has to learn.
 *
 * LARK moves an on-chain deposit into the Ark so it can be spent, and none of that is the user's
 * problem: they sent bitcoin and they want to spend it. Every term here names a piece of the
 * machinery, and putting any of them on an ordinary screen asks the user to hold a model of two
 * places their money can live in order to understand a wait.
 *
 * Whole-word matching, because the product is called LARK and that contains "ark".
 */
private val FORBIDDEN = listOf("board", "boarding", "boarded", "ark", "arks", "vtxo", "vtxos")

/**
 * Screens exempted, with the reason.
 *
 * Both exist precisely for people who want the machinery — Advanced is "the machinery, for people
 * who want it", and the technical-details screen is the per-payment version of the same offer.
 * Calling a VTXO anything else on either would be a different kind of dishonesty.
 */
private val EXEMPT = setOf("AdvancedScreen.kt", "TxTechScreen.kt")

/** Matches the contents of a Kotlin double-quoted string literal, which is where copy lives. */
private val STRING_LITERAL = Regex("\"([^\"\\\\\n]|\\\\.)*\"")

/**
 * Fails the build when protocol vocabulary reaches a user-facing screen.
 *
 * A source scan rather than a model assertion, because copy is written inline in composables and
 * there is no single string table to inspect. It lives in `androidUnitTest` rather than
 * `commonTest` for the plain reason that `commonTest` also compiles for iOS, where there is no
 * filesystem to read the sources from.
 *
 * Sibling in posture to `ShippedCoreConfigTest`: a rule that review keeps having to remember is a
 * rule that decays, so it is asserted instead.
 */
class CopyVocabularyTest {

    @Test
    fun noUserFacingScreenTeachesTheUserAboutTheProtocol() {
        val screens = screensDir().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(
            screens.size > 10,
            "found only ${screens.size} screen sources — the scan is looking in the wrong place",
        )

        val offences = screens
            .filterNot { it.name in EXEMPT }
            .flatMap { file -> offencesIn(file) }

        if (offences.isNotEmpty()) {
            fail(
                "Protocol vocabulary reached a user-facing screen:\n" +
                    offences.joinToString("\n") { "  $it" } +
                    "\n\nSay what the user can do with their money, not where it is going. " +
                    "If this really belongs on the Advanced screen, put it there.",
            )
        }
    }

    /** Every forbidden whole word appearing inside a string literal in [file], with its line. */
    private fun offencesIn(file: File): List<String> =
        file.readLines().withIndex().flatMap { (index, line) ->
            STRING_LITERAL.findAll(line)
                .flatMap { literal -> wordsIn(literal.value).filter { it in FORBIDDEN } }
                .distinct()
                .map { word -> "${file.name}:${index + 1} — \"$word\" in: ${line.trim()}" }
                .toList()
        }

    private fun wordsIn(text: String): Sequence<String> =
        Regex("[A-Za-z]+").findAll(text.lowercase()).map { it.value }

    /**
     * The screens directory, found by walking up from wherever the test runner started.
     *
     * Gradle's working directory for unit tests is not guaranteed to be the module root, and it
     * differs between a local run and CI — resolving a relative path would make this pass by
     * accident in one and fail in the other.
     */
    private fun screensDir(): File {
        val relative = "composeApp/src/commonMain/kotlin/xyz/lark/app/ui/screens"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("could not locate $relative from ${File("").absolutePath}")
    }
}
