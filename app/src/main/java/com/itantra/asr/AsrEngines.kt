package com.itantra.asr

import com.itantra.lang.LanguagePack

/**
 * Picks the recogniser for a pack.
 *
 * The choice comes from the pack's declared model type, never from its language code.
 * That keeps the "adding a language is a data task" guarantee intact even when the new
 * language needs a different architecture: a pack that says `"type": "whisper"` gets a
 * Whisper engine, and nothing in the app has to learn what Tamil is.
 */
object AsrEngines {

    fun create(pack: LanguagePack): AsrEngine? = when (pack.asr?.type) {
        null -> null
        LanguagePack.AsrModel.TYPE_WHISPER -> WhisperAsrEngine(pack)
        else -> SherpaAsrEngine(pack)
    }

    /** Loading is the slow part, so the caller decides which thread it happens on. */
    fun load(engine: AsrEngine?): Boolean = when (engine) {
        null -> false
        is WhisperAsrEngine -> engine.load()
        is SherpaAsrEngine -> engine.load()
        else -> engine.load()
    }

    fun lastError(engine: AsrEngine?): String? = when (engine) {
        is WhisperAsrEngine -> engine.lastError
        is SherpaAsrEngine -> engine.lastError
        else -> null
    }
}
