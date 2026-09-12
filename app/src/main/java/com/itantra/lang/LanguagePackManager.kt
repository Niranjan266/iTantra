package com.itantra.lang

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Finds installed languages, and installs the bundled one on first run
 * (PRD F-40 … F-42, TRD section 7).
 *
 * This is the ONLY class permitted to know where models live or what they are called.
 * Everything else receives a [LanguagePack]. The audit for that is a grep: no language
 * name and no `.onnx` filename may appear anywhere else in Kotlin source.
 *
 * ## Why packs are copied out of the APK
 *
 * The app ships one language inside its own assets so that installing the APK is all
 * anyone has to do — it works on any phone, immediately, with no adb push and no
 * network. That is what "works anywhere" has to mean for a device carried into a
 * disaster zone.
 *
 * On first run the bundled pack is copied to app storage, and from then on the pack
 * directory is just a directory. Additional languages are dropped in beside it. So the
 * bundled pack is a convenience, not a special case: it takes exactly the same path
 * through the code as a language added later.
 *
 * Copying is required rather than merely convenient — espeak-ng needs a real filesystem
 * directory and cannot read from inside an APK.
 */
class LanguagePackManager(context: Context) {

    companion object {
        private const val TAG = "iTantra.Packs"
        private const val ASSET_ROOT = "languages"
        private const val MANIFEST = "manifest.json"

        /** Bump to force a re-copy after the bundled models change. */
        /**
         * Bump this whenever anything under `assets/languages/` changes.
         *
         * The stamp filename embeds the number, so a new value means the old stamp is not
         * found and the pack is copied out again. **Forgetting to bump it is silent**: the
         * app keeps running on the previously extracted copy and the new file simply is
         * not there.
         *
         * That is not hypothetical. Adding `phrases.txt` to the bundled English pack
         * shipped correctly inside the APK and never reached the device, because the copy
         * had completed weeks earlier under v1. The English codebook — 144 phrases — read
         * as empty, so compression and cross-language delivery were both quietly inert
         * while the screen cheerfully said "none yet".
         *
         * v2: added `phrases.txt` (the phrase codebook).
         */
        private const val BUNDLED_VERSION = 2
        private const val STAMP = ".installed_v$BUNDLED_VERSION"
    }

    private val appContext = context.applicationContext

    /** Where the bundled pack is unpacked. Internal storage: not user-reachable. */
    val packsDir: File = File(appContext.filesDir, "languages")

    /**
     * Where a hand-installed pack goes: `/sdcard/Android/data/com.itantra/files/languages`.
     *
     * This directory exists because the internal one cannot be written by anybody but
     * the app — not a file manager, not `adb push`, not a teammate with a cable. A
     * "just drop in a folder" design that lands in a directory nobody can reach is not
     * a design, and the claim in PRD section 10.5 was untestable until this was added.
     *
     * Scanning both means the bundled pack and a side-loaded one take the same code
     * path and are indistinguishable once found (TRD section 7).
     */
    val externalPacksDir: File? = appContext.getExternalFilesDir("languages")

    /**
     * Copy the bundled pack out of the APK if it is not already there.
     *
     * Idempotent and safe to call on every launch: a stamp file records completion, so
     * the ~60 MB copy happens once. Writes to a temporary directory and renames on
     * success, so an interrupted first run (battery dies, user force-stops) cannot
     * leave a half-copied pack that looks installed.
     *
     * @return true if anything was installed by this call.
     */
    fun installBundledIfNeeded(): Boolean {
        val stamp = File(packsDir, STAMP)
        if (stamp.isFile) return false

        Log.i(TAG, "installing bundled language pack(s)")
        val started = System.nanoTime()

        return runCatching {
            // Clear any older or partial install.
            if (packsDir.exists()) packsDir.deleteRecursively()
            packsDir.mkdirs()

            val staging = File(appContext.filesDir, "languages.tmp")
            if (staging.exists()) staging.deleteRecursively()
            staging.mkdirs()

            copyAssetDir(ASSET_ROOT, staging)

            if (packsDir.exists()) packsDir.deleteRecursively()
            if (!staging.renameTo(packsDir)) {
                throw IllegalStateException("could not move staged packs into place")
            }

            stamp.writeText(BUNDLED_VERSION.toString())
            val ms = (System.nanoTime() - started) / 1_000_000
            Log.i(TAG, "bundled packs installed in $ms ms")
            true
        }.getOrElse {
            Log.e(TAG, "failed to install bundled packs", it)
            false
        }
    }

    /**
     * Every usable pack found on disk, in manifest order where a manifest exists.
     *
     * A directory containing a valid `pack.json` counts even if the manifest does not
     * list it — that is what makes "drop in a folder" work without editing anything.
     */
    fun installed(): List<LanguagePack> {
        val dirs = listOfNotNull(packsDir, externalPacksDir).filter { it.isDirectory }
        if (dirs.isEmpty()) return emptyList()

        val found = dirs
            .flatMap { it.listFiles { f -> f.isDirectory }.orEmpty().toList() }
            .mapNotNull { LanguagePack.load(it) }
            .filter { it.isUsable }
            // A side-loaded pack with the same id as a bundled one wins: that is how
            // someone replaces the shipped model with a better one.
            .distinctBy { it.id }

        val order = manifestOrder()
        return found.sortedWith(
            compareBy(
                { order.indexOf(it.code).let { i -> if (i < 0) Int.MAX_VALUE else i } },
                { it.id },
            )
        )
    }

    fun byCode(code: String): LanguagePack? = installed().firstOrNull { it.code == code }

    fun byId(id: Int): LanguagePack? = installed().firstOrNull { it.id == id }

    /** Preferred display order, or empty if there is no readable manifest. */
    private fun manifestOrder(): List<String> {
        val f = File(packsDir, MANIFEST)
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONObject(f.readText()).getJSONArray("installed")
            (0 until arr.length()).map { arr.getJSONObject(it).getString("code") }
        }.getOrDefault(emptyList())
    }

    /** Recursive asset copy. AssetManager has no "is this a directory" call, so a */
    /** node with no children is treated as a file. */
    private fun copyAssetDir(assetPath: String, destDir: File) {
        val children = appContext.assets.list(assetPath).orEmpty()

        if (children.isEmpty()) {
            val out = File(destDir.parentFile, destDir.name)
            out.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input ->
                out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            return
        }

        destDir.mkdirs()
        for (child in children) {
            copyAssetDir("$assetPath/$child", File(destDir, child))
        }
    }
}
