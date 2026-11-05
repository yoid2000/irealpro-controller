package com.paul.voicekeys

import java.text.Normalizer
import java.util.Locale
import java.util.UUID

enum class OutputKey(val label: String, val usage: Int, val modifier: Int = 0) {
    COMMA("Comma ,", 0x36), SPACE("Space", 0x2c), PERIOD("Period .", 0x37),
    ENTER("Enter", 0x28), ESCAPE("Escape", 0x29), TAB("Tab", 0x2b),
    BACKSPACE("Backspace", 0x2a), LEFT("Left arrow", 0x50), RIGHT("Right arrow", 0x4f),
    UP("Up arrow", 0x52), DOWN("Down arrow", 0x51),
    A("A", 4), B("B", 5), C("C", 6), D("D", 7), E("E", 8), F("F", 9),
    G("G", 10), H("H", 11), I("I", 12), J("J", 13), K("K", 14), L("L", 15),
    M("M", 16), N("N", 17), O("O", 18), P("P", 19), Q("Q", 20), R("R", 21),
    S("S", 22), T("T", 23), U("U", 24), V("V", 25), W("W", 26), X("X", 27),
    Y("Y", 28), Z("Z", 29),
    ONE("1", 30), TWO("2", 31), THREE("3", 32), FOUR("4", 33), FIVE("5", 34),
    SIX("6", 35), SEVEN("7", 36), EIGHT("8", 37), NINE("9", 38), ZERO("0", 39),
    MINUS("Minus -", 45), EQUALS("Equals =", 46), SLASH("Slash /", 56),
    SEMICOLON("Semicolon ;", 51), APOSTROPHE("Apostrophe '", 52);

    fun report(): ByteArray = byteArrayOf(modifier.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0)
}

data class CommandMapping(
    val id: String = UUID.randomUUID().toString(),
    val word: String,
    val key: OutputKey,
    val threshold: Float = 0.5f,
)

fun normalizeWord(input: String): String = Normalizer.normalize(input, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).trim()

fun validateWord(input: String): String {
    val word = normalizeWord(input)
    require(word.matches(Regex("[a-z]+(?:'[a-z]+)?"))) {
        "Use one English word containing letters and an optional apostrophe."
    }
    require(word.length <= 32) { "Choose a shorter command word." }
    return word
}

fun defaultMappings(): List<CommandMapping> = listOf(
    "one" to OutputKey.COMMA, "two" to OutputKey.COMMA,
    "three" to OutputKey.COMMA, "four" to OutputKey.COMMA,
    "start" to OutputKey.SPACE, "stop" to OutputKey.SPACE, "pause" to OutputKey.PERIOD,
).map { (word, key) -> CommandMapping(id = word, word = word, key = key,
    threshold = if (word == "pause") 0.4f else 0.5f) }

/** FIFO scheduling preserves every occurrence, including repeated identical words. */
class KeyPacingQueue {
    private val queue = ArrayDeque<OutputKey>()
    private var lastCommaAt: Long? = null
    private var lastKeyAt: Long? = null
    val size: Int get() = queue.size

    fun enqueue(key: OutputKey) {
        check(queue.size < 16) { "Too many pending keys; voice control stopped." }
        queue.addLast(key)
    }

    fun waitMillis(now: Long): Long? {
        val key = queue.firstOrNull() ?: return null
        val releaseReady = lastKeyAt?.plus(40) ?: now
        val commaReady = if (key == OutputKey.COMMA) lastCommaAt?.plus(250) ?: now else now
        return (maxOf(releaseReady, commaReady) - now).coerceAtLeast(0)
    }

    fun take(now: Long): OutputKey {
        check(waitMillis(now) == 0L)
        return queue.removeFirst().also {
            lastKeyAt = now
            if (it == OutputKey.COMMA) lastCommaAt = now
        }
    }

    // Cancelling pending keys must not bypass the rate limit for an already sent key.
    fun clear() { queue.clear() }
}
