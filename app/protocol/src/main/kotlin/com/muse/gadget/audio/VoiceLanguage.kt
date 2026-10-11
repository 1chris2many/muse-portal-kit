package com.muse.gadget.audio

/** Portal's stock engine uses ISO-639-2 `eng`; most Android engines use `en`. */
fun isEnglishVoiceLanguage(language: String): Boolean = language.lowercase() in setOf("en", "eng")
