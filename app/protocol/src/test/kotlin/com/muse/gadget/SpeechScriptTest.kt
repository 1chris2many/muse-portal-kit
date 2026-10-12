package com.muse.gadget

import com.muse.gadget.audio.SpeechScript
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpeechScriptTest {
    private fun texts(reply: String) = SpeechScript.plan(reply).map { it.text }

    @Test fun `markdown marks are not spoken`() {
        assertEquals("Bold, italics and code stay as words.",
            SpeechScript.plain("**Bold**, *italics* and `code` stay as words."))
        assertEquals("Heading\nquoted line\nfirst item\nsecond item",
            SpeechScript.plain("## Heading\n> quoted line\n- first item\n+ second item"))
        assertEquals("under scored words", SpeechScript.plain("__under__ _scored_ words"))
    }

    @Test fun `inline punctuation that is not markup survives`() {
        assertEquals("Score 2-1, pick #1, a > b, snake_case_name.",
            SpeechScript.plain("Score 2-1, pick #1, a > b, snake_case_name."))
        assertEquals("1. First\n2. Second", SpeechScript.plain("1. First\n2. Second"))
    }

    @Test fun `tables read as rows and lose their separator`() {
        val plain = SpeechScript.plain("| Day | Weather |\n|---|:---:|\n| Mon | Rain |")
        assertEquals("Day, Weather\nMon, Rain", plain)
        val script = SpeechScript.plan("| Day | Weather |\n|---|:---:|\n| Mon | Rain |\n\nDone.")
        assertEquals(listOf("Day, Weather", "Mon, Rain", "Done."), script.map { it.text })
        assertEquals(SpeechScript.ITEM_PAUSE_MS, script[0].pauseAfterMs)
        assertEquals(SpeechScript.ITEM_PAUSE_MS, script[1].pauseAfterMs)
        assertEquals(0, script[2].pauseAfterMs)
    }

    @Test fun `links and urls become their visible text or host`() {
        assertEquals("See the docs and example.com.",
            SpeechScript.plain("See [the docs](https://docs.example.com/x?y=1) and https://www.example.com/path/to/page."))
        assertEquals("a diagram here", SpeechScript.plain("![a diagram](https://example.com/d.png) here"))
        assertEquals("example.org", SpeechScript.plain("<https://example.org/a>"))
    }

    @Test fun `tags and SSML never reach the engine`() {
        val reply = "<speak><prosody rate=\"fast\">Hi</prosody><break time=\"3s\"/> there</speak><!-- note -->"
        val plain = SpeechScript.plain(reply)
        assertEquals("Hi there", plain)
        assertFalse(plain.contains('<'))
        assertFalse(plain.contains('>'))
        assertEquals("Tom & Jerry \"quoted\"", SpeechScript.plain("Tom &amp; Jerry &quot;quoted&quot;"))
    }

    @Test fun `encoded markup is decoded and then dropped while comparisons survive`() {
        assertEquals("Hi", SpeechScript.plain("&lt;speak&gt;Hi&lt;/speak&gt;"))
        assertEquals("Hi there", SpeechScript.plain("&#60;prosody rate=\"fast\"&#62;Hi&#x3C;/prosody&#x3E; there"))
        assertEquals("a < b and c > d", SpeechScript.plain("a &lt; b and c &gt; d"))
        assertEquals("5 > 3 < 9", SpeechScript.plain("5 > 3 < 9"))
        val everything = SpeechScript.plain("**&lt;speak&gt;** `&lt;break/&gt;` _&#60;b&#62;_")
        assertFalse(everything.contains('<'))
        assertFalse(everything.contains('>'))
    }

    @Test fun `literal arithmetic keeps its operators`() {
        assertEquals("2 times 3 = 6", SpeechScript.plain("2*3 = 6"))
        assertEquals("2 times 3 = 6", SpeechScript.plain("2 * 3 = 6"))
        assertEquals("(a+b) times (c+d)", SpeechScript.plain("(a+b)*(c+d)"))
        assertEquals("x = a * 2", SpeechScript.plain("`x = a * 2`"))
        assertEquals("total = price * qty", SpeechScript.plain("```python\ntotal = price * qty\n```"))
        assertEquals("bold and 2 times 3", SpeechScript.plain("**bold** and 2*3"))
        assertEquals("note, lone", SpeechScript.plain("*note*, lone"))
        assertEquals("*unpaired star", SpeechScript.plain("*unpaired star"))
    }

    @Test fun `code fences keep their content without the fence`() {
        assertEquals("Run this:\nmake test\nthen rest.",
            SpeechScript.plain("Run this:\n```sh\nmake test\n```\nthen rest."))
    }

    @Test fun `emoji and pictographs are dropped and arrows read as to`() {
        assertEquals("Great job! Home to work.",
            SpeechScript.plain("Great job! 🎉✅ Home → work. 👍🏽"))
    }

    @Test fun `sentences pause and paragraphs pause longer`() {
        val script = SpeechScript.plan("First sentence is here. Second sentence follows it.\n\nNew paragraph starts now.")
        assertEquals(listOf("First sentence is here.", "Second sentence follows it.", "New paragraph starts now."),
            script.map { it.text })
        assertEquals(SpeechScript.SENTENCE_PAUSE_MS, script[0].pauseAfterMs)
        assertEquals(SpeechScript.PARAGRAPH_PAUSE_MS, script[1].pauseAfterMs)
        assertEquals(0, script[2].pauseAfterMs)
    }

    @Test fun `soft wrapped lines belong to one paragraph`() {
        assertEquals(listOf("One line wraps here and keeps going on the next."),
            texts("One line wraps here\nand keeps going on the next."))
    }

    @Test fun `short fragments merge instead of pausing`() {
        assertEquals(listOf("Yes. I can help with that right away."),
            texts("Yes. I can help with that right away."))
        assertEquals(listOf("Here is the longer sentence that ends things. OK."),
            texts("Here is the longer sentence that ends things. OK."))
    }

    @Test fun `questions exclamations and headings get prosody hints`() {
        val script = SpeechScript.plan("# Weather\nIs it raining today?\nWhat a storm it is!\nIt is calm now.")
        assertEquals(SpeechScript.HEADING_RATE, script[0].rate)
        assertEquals(SpeechScript.QUESTION_PITCH, script[1].pitch)
        assertEquals(SpeechScript.NEUTRAL_RATE, script[1].rate)
        assertEquals(SpeechScript.EXCLAMATION_RATE, script[2].rate)
        assertEquals(SpeechScript.EXCLAMATION_PITCH, script[2].pitch)
        assertEquals(SpeechScript.NEUTRAL_RATE, script[3].rate)
        assertEquals(SpeechScript.NEUTRAL_PITCH, script[3].pitch)
        assertEquals(SpeechScript.QUESTION_PITCH, SpeechScript.plan("\"Is it over?\"")[0].pitch)
    }

    @Test fun `list items pause as items and headings end their block`() {
        val script = SpeechScript.plan("## Plan\n- Buy milk today\n- Walk the dog\nThen rest.")
        assertEquals(listOf("Plan", "Buy milk today", "Walk the dog", "Then rest."), script.map { it.text })
        assertEquals(SpeechScript.PARAGRAPH_PAUSE_MS, script[0].pauseAfterMs)
        assertEquals(SpeechScript.ITEM_PAUSE_MS, script[1].pauseAfterMs)
        assertEquals(SpeechScript.ITEM_PAUSE_MS, script[2].pauseAfterMs)
        assertEquals(0, script[3].pauseAfterMs)
    }

    @Test fun `long sentences split under the engine limit at clause boundaries`() {
        val clause = "this clause keeps going for a while, "
        val sentence = clause.repeat(12).trimEnd().trimEnd(',') + "."
        val script = SpeechScript.plan(sentence, maxChars = 100)
        assertTrue(script.size > 1)
        assertTrue(script.all { it.text.length <= 100 })
        assertTrue(script.dropLast(1).all { it.pauseAfterMs == 0 })
        assertEquals(sentence.replace(", ", ",").replace(" ", ""),
            script.joinToString("") { it.text }.replace(", ", ",").replace(" ", ""))
        val unbroken = "a".repeat(250)
        assertEquals(listOf(100, 100, 50), SpeechScript.plan(unbroken, maxChars = 100).map { it.text.length })
    }

    @Test fun `default limit stays under the stock engine input length`() {
        assertTrue(SpeechScript.MAX_UTTERANCE_CHARS < 4000)
        assertTrue(SpeechScript.plan("word ".repeat(2000)).all { it.text.length <= SpeechScript.MAX_UTTERANCE_CHARS })
    }

    @Test fun `nothing to say yields an empty script`() {
        assertTrue(SpeechScript.plan("").isEmpty())
        assertTrue(SpeechScript.plan("   \n\n").isEmpty())
        assertTrue(SpeechScript.plan("***\n---").isEmpty())
        assertTrue(SpeechScript.plan("🎉 ✅").isEmpty())
        assertTrue(SpeechScript.plan("<speak></speak>").isEmpty())
    }
}
