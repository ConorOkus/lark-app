package xyz.lark.app.core

/**
 * What the app can tell about a typed recovery phrase before it asks a core to open anything.
 *
 * Shape only — word count and character set. It deliberately does not check the BIP-39 wordlist or
 * the checksum: that would mean shipping the 2048-word list in shared code to duplicate a check the
 * crate already does properly, and getting it subtly wrong would reject a valid phrase, which is
 * the one failure a recovery screen must never produce. So the rule here is "could plausibly be a
 * phrase" — enough to keep the Restore button honest — and the crate remains the authority.
 */
object MnemonicInput {

    /**
     * BIP-39 allows these five lengths; lark generates the shortest and accepts any of them, so a
     * wallet made elsewhere can be restored here.
     */
    private val VALID_WORD_COUNTS = setOf(
        WORDS_128_BIT,
        WORDS_160_BIT,
        WORDS_192_BIT,
        WORDS_224_BIT,
        WORDS_256_BIT,
    )

    /** Lowercase latin letters only — every word in every BIP-39 wordlist lark supports. */
    private val WORD_SHAPE = Regex("[a-z]+")

    /**
     * Splits [raw] into words, tolerating the ways people actually paste a phrase: any whitespace
     * as a separator, stray blank runs, mixed case, and leading or trailing space.
     */
    fun words(raw: String): List<String> =
        raw.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }

    /** Whether [raw] is shaped like a phrase a core could try to open. */
    fun isPlausible(raw: String): Boolean {
        val words = words(raw)
        return words.size in VALID_WORD_COUNTS && words.all { WORD_SHAPE.matches(it) }
    }

    /** How many words have been typed so far, for progress copy. */
    fun wordCount(raw: String): Int = words(raw).size

    private val WHITESPACE = Regex("\\s+")
}

// One length per BIP-39 entropy size; named because "15" on its own says nothing.
private const val WORDS_128_BIT = 12
private const val WORDS_160_BIT = 15
private const val WORDS_192_BIT = 18
private const val WORDS_224_BIT = 21
private const val WORDS_256_BIT = 24
