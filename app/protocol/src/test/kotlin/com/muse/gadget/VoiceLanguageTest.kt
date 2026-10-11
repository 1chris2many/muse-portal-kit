package com.muse.gadget

import com.muse.gadget.audio.isEnglishVoiceLanguage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class VoiceLanguageTest {
    @Test fun `English accepts Android and Portal stock locale codes`() {
        assertTrue(isEnglishVoiceLanguage("en"))
        assertTrue(isEnglishVoiceLanguage("eng"))
        assertFalse(isEnglishVoiceLanguage("deu"))
        assertFalse(isEnglishVoiceLanguage(""))
    }
}
