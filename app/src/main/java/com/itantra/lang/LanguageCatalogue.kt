package com.itantra.lang

import android.content.Context
import com.itantra.codec.LanguageId
import org.json.JSONObject

/**
 * What languages exist, and where to get them — read from `assets/languages/catalogue.json`.
 *
 * The catalogue is **data**. Adding an eleventh language means editing that file, not this
 * one: nothing here names a language, a model or a URL.
 *
 * ## Ids are not in the catalogue, on purpose
 *
 * A language id is part of the frozen wire format and can never be renumbered — a packet
 * recorded today has to decode to the same language in ten years. Listing the numbers in
 * the catalogue as well would make two sources of truth for something that must have
 * exactly one, so the id is resolved here from the ISO code through [LanguageId].
 *
 * That is not a theoretical tidiness argument. The first draft of the catalogue carried
 * its own ids and **six of the ten were wrong**, which would have put Bengali on the wire
 * as Gujarati and Telugu as Marathi.
 */
data class CatalogueEntry(
    /** ISO 639-1 code, and the pack directory name. */
    val code: String,
    /** The language's own name in its own script. Never translated. */
    val name: String,
    /** Meta's three-letter code for the MMS voice, for the converter. */
    val mmsCode: String,
    /** True for the language shipped inside the APK. */
    val bundled: Boolean,
    /** The frozen wire id. Null means this code is not in the frozen table. */
    val langId: Int?,
    val asrModelUrl: String,
    val asrTokensUrl: String,
    val approxMb: Int,
) {
    /** Usable only if the wire format knows this language. */
    val isKnown: Boolean get() = langId != null
}

object LanguageCatalogue {

    private const val ASSET = "languages/catalogue.json"

    /**
     * Read the catalogue. Returns empty rather than throwing: a malformed catalogue must
     * cost the download list, never the app.
     */
    fun load(context: Context): List<CatalogueEntry> = runCatching {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        val root = JSONObject(text)

        val asr = root.getJSONObject("asr")
        val modelTemplate = asr.getString("modelUrl")
        val tokensUrl = asr.getString("tokensUrl")
        val asrMb = asr.optInt("approxModelMb", 0)
        val ttsMb = root.optJSONObject("tts")?.optInt("approxModelMb", 0) ?: 0

        val out = mutableListOf<CatalogueEntry>()
        val langs = root.getJSONArray("languages")
        for (i in 0 until langs.length()) {
            val l = langs.getJSONObject(i)
            val code = l.getString("code")
            out += CatalogueEntry(
                code = code,
                name = l.getString("name"),
                mmsCode = l.optString("mms"),
                bundled = l.optBoolean("bundled", false),
                // The single source of truth for the wire id.
                langId = LanguageId.idOfIsoCode(code),
                asrModelUrl = modelTemplate.replace("{code}", code),
                asrTokensUrl = tokensUrl,
                approxMb = asrMb + ttsMb,
            )
        }
        out
    }.getOrElse { emptyList() }

    /**
     * The catalogue, minus what is already on the phone.
     *
     * Matching is by pack directory code rather than by id, because a pack that is present
     * but unusable — half-copied, missing its model — should still count as "installed" for
     * this purpose. Offering to download over the top of it would not fix it.
     */
    fun available(context: Context, installed: List<LanguagePack>): List<CatalogueEntry> {
        val have = installed.map { it.code }.toSet()
        return load(context).filter { it.code !in have && it.isKnown }
    }
}
