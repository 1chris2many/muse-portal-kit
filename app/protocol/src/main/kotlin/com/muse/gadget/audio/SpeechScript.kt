package com.muse.gadget.audio

import java.text.BreakIterator
import java.util.Locale

/**
 * Turns a Muse reply into a speech script for an installed offline Android voice.
 *
 * Android `TextToSpeech` is not assumed to interpret SSML, so expressiveness is
 * limited to what every engine accepts per utterance: plain text, a speech rate,
 * a pitch, and a silent pause queued after it. Markdown marks, tables, links,
 * tags and emoji are removed first; nothing tag-shaped ever reaches the engine,
 * where some engines would read it aloud.
 *
 * Pure JVM, no Android dependencies, unit-tested offline. Display text is not
 * changed by this; captions keep the reply as Muse sent it.
 */
object SpeechScript {
    /** One engine request. [pauseAfterMs] is silence queued after it. */
    data class Utterance(val text: String, val pauseAfterMs: Int, val rate: Float, val pitch: Float)

    /** Below `TextToSpeech.getMaxSpeechInputLength()` (4000 on stock engines). */
    const val MAX_UTTERANCE_CHARS = 3500
    const val SENTENCE_PAUSE_MS = 160
    const val ITEM_PAUSE_MS = 260
    const val PARAGRAPH_PAUSE_MS = 420

    const val NEUTRAL_RATE = 1.0f
    const val NEUTRAL_PITCH = 1.0f
    const val QUESTION_PITCH = 1.06f
    const val EXCLAMATION_RATE = 1.04f
    const val EXCLAMATION_PITCH = 1.03f
    const val HEADING_RATE = 0.96f

    /** Sentence fragments shorter than this join the next sentence instead of pausing. */
    private const val MERGE_BELOW_CHARS = 16

    private enum class Kind { TEXT, ITEM, HEADING, BLANK }
    private data class Line(val text: String, val kind: Kind)
    private data class Block(val text: String, val kind: Kind)

    /** The reply as the voice would read it, one block per line. */
    fun plain(reply: String): String = blocks(reply).joinToString("\n") { it.text }

    /** Utterances in speaking order; empty for a reply with nothing to say. */
    fun plan(reply: String, maxChars: Int = MAX_UTTERANCE_CHARS): List<Utterance> {
        require(maxChars > 0) { "maxChars must be positive" }
        val blocks = blocks(reply)
        val out = ArrayList<Utterance>()
        blocks.forEachIndexed { b, block ->
            val sentences = sentences(block.text)
            sentences.forEachIndexed { i, sentence ->
                val endOfBlock = i == sentences.lastIndex
                val pause = when {
                    endOfBlock && b == blocks.lastIndex -> 0
                    endOfBlock && block.kind == Kind.ITEM -> ITEM_PAUSE_MS
                    endOfBlock -> PARAGRAPH_PAUSE_MS
                    else -> SENTENCE_PAUSE_MS
                }
                val chunks = chunk(sentence, maxChars)
                chunks.forEachIndexed { c, text ->
                    val (rate, pitch) = prosody(text, block.kind)
                    out += Utterance(text, if (c == chunks.lastIndex) pause else 0, rate, pitch)
                }
            }
        }
        return out
    }

    // ---------- structure ----------

    private fun blocks(reply: String): List<Block> {
        val out = ArrayList<Block>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotEmpty()) {
                out += Block(paragraph.toString(), Kind.TEXT)
                paragraph.setLength(0)
            }
        }
        for (line in lines(reply)) when (line.kind) {
            Kind.BLANK -> flush()
            Kind.TEXT -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(line.text)
            }
            else -> {
                flush()
                out += Block(line.text, line.kind)
            }
        }
        flush()
        return out
    }

    private fun lines(reply: String): List<Line> {
        var text = reply.replace("\r\n", "\n").replace('\r', '\n')
        text = FENCE.replace(text) { "\n" + it.groupValues[1] + "\n" }
        text = AUTOLINK.replace(text) { host(it.groupValues[1]) }
        text = TAG.replace(text, " ")
        text = IMAGE.replace(text) { it.groupValues[1] }
        text = LINK.replace(text) { it.groupValues[1] }
        text = URL.replace(text) { host(it.value) }
        text = entities(text)

        val out = ArrayList<Line>()
        for (raw in text.split('\n')) {
            val trimmed = raw.trim()
            var line = trimmed
            var kind = Kind.TEXT
            when {
                trimmed.isEmpty() || RULE.matches(trimmed) -> {
                    out += Line("", Kind.BLANK)
                    continue
                }
                TABLE_SEPARATOR.matches(trimmed) -> continue
                trimmed.startsWith("|") -> {
                    line = trimmed.trim('|').split('|').map { it.trim() }
                        .filter { it.isNotEmpty() }.joinToString(", ")
                    kind = Kind.ITEM
                }
                HEADING.containsMatchIn(trimmed) -> {
                    line = HEADING.replace(trimmed, "")
                    kind = Kind.HEADING
                }
                else -> {
                    line = QUOTE.replace(trimmed, "")
                    val unbulleted = BULLET.replace(line, "")
                    if (unbulleted != line) {
                        line = unbulleted
                        kind = Kind.ITEM
                    } else if (NUMBERED.containsMatchIn(line)) {
                        kind = Kind.ITEM
                    }
                }
            }
            line = inline(line)
            if (line.isEmpty()) {
                out += Line("", Kind.BLANK)
                continue
            }
            out += Line(line, kind)
        }
        return out
    }

    private fun inline(line: String): String {
        var s = STAR.replace(line, "")
        s = s.replace("`", "").replace("~~", "")
        s = UNDERSCORE_EMPHASIS.replace(s) { it.groupValues[1] }
        s = symbols(s)
        return WHITESPACE.replace(s, " ").trim()
    }

    private fun sentences(block: String): List<String> {
        val iterator = BreakIterator.getSentenceInstance(Locale.US)
        iterator.setText(block)
        val raw = ArrayList<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val sentence = block.substring(start, end).trim()
            if (sentence.isNotEmpty()) raw += sentence
            start = end
            end = iterator.next()
        }
        val out = ArrayList<String>()
        var carry = ""
        for (sentence in raw) {
            val joined = if (carry.isEmpty()) sentence else "$carry $sentence"
            // A question or exclamation keeps its own utterance so its prosody hint applies.
            if (joined.length < MERGE_BELOW_CHARS && !joined.endsWith("?") && !joined.endsWith("!")) {
                carry = joined
                continue
            }
            out += joined
            carry = ""
        }
        if (carry.isNotEmpty()) {
            val previous = out.lastOrNull()
            if (previous == null || previous.endsWith("?") || previous.endsWith("!")) out += carry
            else out[out.lastIndex] = "$previous $carry"
        }
        return out
    }

    private fun chunk(sentence: String, maxChars: Int): List<String> {
        if (sentence.length <= maxChars) return listOf(sentence)
        val out = ArrayList<String>()
        var rest = sentence
        while (rest.length > maxChars) {
            val window = rest.substring(0, maxChars)
            var cut = CLAUSE_END.findAll(window).lastOrNull()?.range?.last?.plus(1) ?: -1
            if (cut < maxChars / 4) cut = window.lastIndexOf(' ')
            if (cut < maxChars / 4) cut = maxChars
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }

    private fun prosody(text: String, kind: Kind): Pair<Float, Float> {
        val end = text.trimEnd('"', '\'', ')', ']', '”', '’')
        return when {
            kind == Kind.HEADING -> HEADING_RATE to NEUTRAL_PITCH
            end.endsWith("?") -> NEUTRAL_RATE to QUESTION_PITCH
            end.endsWith("!") -> EXCLAMATION_RATE to EXCLAMATION_PITCH
            else -> NEUTRAL_RATE to NEUTRAL_PITCH
        }
    }

    // ---------- text cleanup ----------

    private fun host(url: String): String {
        val trailing = url.takeLastWhile { it in ".,;:!?" }
        val core = url.dropLast(trailing.length)
        val host = core.substringAfter("://").substringBefore('/').substringBefore('?')
            .substringBefore('#').substringBefore(':').removePrefix("www.")
        return (if (host.isEmpty()) "" else host) + trailing
    }

    private fun entities(text: String): String = text
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /** Drops emoji and pictographs; reads the two common arrows as "to". */
    private fun symbols(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == 0x2192 || cp == 0x21D2 -> sb.append(" to ")
                isPictograph(cp) -> sb.append(' ')
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }

    private fun isPictograph(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF ||
            cp in 0x2190..0x21FF || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF ||
            cp == 0x200D || cp == 0x20E3 || cp == 0x2022 || cp == 0x2122 || cp == 0x00A9 || cp == 0x00AE

    private val FENCE = Regex("```[^\\n]*\\n([\\s\\S]*?)```")
    private val AUTOLINK = Regex("<(https?://[^\\s<>]+)>")
    private val TAG = Regex("</?[A-Za-z][^<>]*>|<!--[\\s\\S]*?-->|<\\?[^<>]*\\?>")
    private val IMAGE = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val URL = Regex("https?://[^\\s<>()\\[\\]\"']+")
    private val RULE = Regex("([-*_]\\s*){3,}")
    private val TABLE_SEPARATOR = Regex("\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?")
    private val HEADING = Regex("^#{1,6}(\\s+|$)")
    private val QUOTE = Regex("^(>\\s?)+")
    private val BULLET = Regex("^[-+*•]\\s+")
    private val NUMBERED = Regex("^\\d{1,3}[.)]\\s+")
    private val STAR = Regex("(?<=\\S)\\*+|\\*+(?=\\S)")
    private val UNDERSCORE_EMPHASIS = Regex("(?<![\\w])_{1,2}(\\S(?:[^_]*?\\S)?)_{1,2}(?![\\w])")
    private val WHITESPACE = Regex("[ \\t\\u00A0]+")
    private val CLAUSE_END = Regex("[,;:]\\s")
}
