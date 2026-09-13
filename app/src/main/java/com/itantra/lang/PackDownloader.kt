package com.itantra.lang

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Fetches a language pack onto the phone.
 *
 * ## When this runs, and why that is not a contradiction
 *
 * This is the **one** part of iTantra that uses a network, and it runs at setup — while
 * the user still has connectivity — never in the field. Once a pack is on the phone the
 * app never touches a network again for it: recognition, synthesis and every link are
 * local. "Download your languages before you go" is the same bargain as offline maps.
 *
 * The alternative would be shipping ten languages inside the APK, which is ~2.2 GB and
 * would make the app undownloadable on exactly the connections the people who need it
 * have.
 *
 * ## Written to a staging directory and renamed
 *
 * A 188 MB download over a bad connection will be interrupted. Writing straight into the
 * packs directory would leave a half-file that looks like an installed language and fails
 * at load, so the pack is assembled in `<code>.partial` and moved into place only when
 * every file has arrived — the same discipline the bundled-asset installer already uses.
 */
class PackDownloader(private val context: Context) {

    companion object {
        private const val TAG = "iTantra.Download"
        private const val BUFFER = 64 * 1024
    }

    /** Progress for one pack, 0..1, plus the file being fetched. */
    data class Progress(val fraction: Float, val label: String)

    /**
     * Download [entry] into the external packs directory.
     *
     * @param onProgress called on the calling coroutine's context, often.
     * @return the installed pack directory, or null if anything failed.
     */
    suspend fun download(
        entry: CatalogueEntry,
        onProgress: (Progress) -> Unit = {},
    ): File? = withContext(Dispatchers.IO) {
        val root = context.getExternalFilesDir("languages") ?: run {
            Log.e(TAG, "no external files dir")
            return@withContext null
        }
        val staging = File(root, "${entry.code}.partial")
        val target = File(root, entry.code)

        runCatching {
            if (staging.exists()) staging.deleteRecursively()
            File(staging, "asr-nemo").mkdirs()

            onProgress(Progress(0f, "recognition model"))
            fetch(entry.asrModelUrl, File(staging, "asr-nemo/model.int8.onnx")) { f ->
                // The model is ~188 MB of a ~225 MB pack, so it is nearly all the wait.
                onProgress(Progress(f * 0.92f, "recognition model"))
            }

            onProgress(Progress(0.93f, "vocabulary"))
            fetch(entry.asrTokensUrl, File(staging, "asr-nemo/tokens.txt"))

            onProgress(Progress(0.97f, "finishing"))
            writeDescriptor(entry, staging)

            if (target.exists()) target.deleteRecursively()
            if (!staging.renameTo(target)) error("could not move the pack into place")

            onProgress(Progress(1f, "ready"))
            Log.i(TAG, "installed ${entry.code} (${target.absolutePath})")
            target
        }.getOrElse {
            // Cancellation is not a failure and must not leave a warning in the log, but
            // the staging directory has to go either way.
            Log.w(TAG, "download of ${entry.code} did not complete: ${it.message}")
            runCatching { staging.deleteRecursively() }
            null
        }
    }

    /**
     * Write the `pack.json` that makes this directory a language.
     *
     * Generated rather than downloaded: the descriptor describes **this** app's pack
     * format, and fetching it from a model host would tie our format to a third party who
     * has never heard of us.
     *
     * No `tts` block. The MMS voices are not published as ONNX and have to be converted
     * (tools/export-mms-tts.py), so a freshly downloaded language **understands you but
     * cannot speak yet** — which the settings screen states per language rather than
     * letting the user discover it by holding the button and hearing nothing.
     */
    private fun writeDescriptor(entry: CatalogueEntry, dir: File) {
        val json = JSONObject().apply {
            put("id", entry.langId ?: error("no wire id for ${entry.code}"))
            put("code", entry.code)
            put("name", entry.name)
            put("asr", JSONObject().apply {
                put("type", LanguagePack.AsrModel.TYPE_NEMO_CTC)
                put("model", "asr-nemo/model.int8.onnx")
                put("tokens", "asr-nemo/tokens.txt")
                put("sampleRate", 16_000)
                put("featureDim", 80)
            })
        }
        File(dir, LanguagePack.PACK_FILE).writeText(json.toString(2))
    }

    private suspend fun fetch(
        url: String,
        into: File,
        onProgress: (Float) -> Unit = {},
    ) {
        into.parentFile?.mkdirs()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            // Model hosts redirect to a CDN; without following it the download is a
            // 300-byte HTML page that fails much later as a corrupt model.
            instanceFollowRedirects = true
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code for $url")
            val total = connection.contentLengthLong

            connection.inputStream.use { input ->
                into.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER)
                    var done = 0L
                    while (true) {
                        // Honour cancellation: a user who backs out of a 188 MB download
                        // should not keep paying for it.
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
