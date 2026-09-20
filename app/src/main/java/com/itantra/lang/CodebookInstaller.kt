package com.itantra.lang

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Puts the phrase codebook into every language pack on the phone.
 *
 * ## Why this exists
 *
 * The codebook is what makes a message cross languages: a phrase travels as its **line
 * number**, and each phone renders that line from its own file. So a language without a
 * codebook cannot translate at all — in either direction.
 *
 * That was the state of things. Only the bundled English pack carried `phrases.txt`;
 * [PackDownloader] wrote none, so a downloaded Hindi pack showed "0 phrases" and a Hindi
 * speaker had no line number to send. The Tamil pack had 32 of 144 lines filled in, so
 * nine phrases in ten fell back to untranslated text.
 *
 * The codebooks are a few kilobytes each, so every one ships inside the APK and is copied
 * into a pack whenever that pack has none or has an older version. A side-loaded pack, a
 * downloaded pack and the bundled one all end up with the same file.
 *
 * ## Version, and why it must never shrink a file
 *
 * [VERSION] forces a refresh when a codebook gains lines. Only ever **append** to a
 * codebook: a line's position is a wire contract, and a phone with a shorter file simply
 * cannot resolve the newer ids — which is handled ([PhraseCodebook.textOf] returns null),
 * whereas a reordered file would decode an id to a real but different sentence.
 */
object CodebookInstaller {

    private const val TAG = "iTantra.Codebook"
    private const val ASSET_DIR = "codebooks"

    /** Bump when any bundled codebook changes. */
    const val VERSION = 1

    private const val STAMP = ".codebook_v"

    /**
     * Copy the bundled codebook into each pack that needs one.
     *
     * Safe to call on every launch and cheap when there is nothing to do: a stamp file
     * per pack records the version already installed.
     *
     * @return how many packs were updated.
     */
    fun installInto(context: Context, packDirs: List<File>): Int {
        val available = runCatching { context.assets.list(ASSET_DIR)?.toSet() }
            .getOrNull().orEmpty()
        if (available.isEmpty()) return 0

        var updated = 0
        for (dir in packDirs) {
            val code = dir.name
            val asset = "$code.txt"
            if (asset !in available) continue
            val stamp = File(dir, STAMP + VERSION)
            if (stamp.isFile) continue

            val ok = runCatching {
                val text = context.assets.open("$ASSET_DIR/$asset")
                    .bufferedReader().use { it.readText() }
                // Written whole: a half-written codebook would resolve some ids and not
                // others, which is harder to notice than no codebook at all.
                val tmp = File(dir, "phrases.txt.tmp")
                tmp.writeText(text)
                val target = File(dir, "phrases.txt")
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) error("could not move the codebook into place")
                // Clear older stamps so the directory does not accumulate them.
                dir.listFiles { f -> f.name.startsWith(STAMP) }?.forEach { it.delete() }
                stamp.writeText(VERSION.toString())
                true
            }.getOrElse {
                Log.w(TAG, "could not install the $code codebook: ${it.message}")
                false
            }
            if (ok) {
                updated++
                Log.i(TAG, "installed the $code codebook (v$VERSION)")
            }
        }
        return updated
    }
}
