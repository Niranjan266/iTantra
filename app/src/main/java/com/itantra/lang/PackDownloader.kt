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
import java.util.zip.ZipInputStream
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

        /** Attempts per file, each resuming where the last stopped. */
        private const val MAX_ATTEMPTS = 6
        private const val RETRY_BASE_MS = 2_000L
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
            val asrPart = if (entry.voiceUrl != null && entry.approxMb > 0)
                entry.asrMb.toFloat() / entry.approxMb * 0.97f else 0.92f
            fetch(entry.asrModelUrl, File(staging, "asr-nemo/model.int8.onnx")) { f ->
                onProgress(Progress(f * asrPart, "recognition model"))
            }

            // The recogniser is the larger share; split the bar by what is actually fetched.
            val asrShare = if (entry.voiceUrl != null && entry.approxMb > 0)
                entry.asrMb.toFloat() / entry.approxMb else 0.92f

            onProgress(Progress(asrShare, "vocabulary"))
            fetch(entry.asrTokensUrl, File(staging, "asr-nemo/tokens.txt"))

            writeDescriptor(entry, staging)

            // The voice last, and not fatal. A language that understands you but cannot
            // speak yet is still useful, and Settings offers the voice again on its own —
            // whereas throwing away a finished 188 MB recogniser because a 50 MB voice
            // was interrupted would make the user pay for both twice.
            if (entry.voiceUrl != null) {
                val ok = runCatching {
                    installVoice(entry, staging) { f ->
                        onProgress(Progress(asrShare + f * (0.98f - asrShare), "voice"))
                    }
                }.onFailure { Log.w(TAG, "voice for ${entry.code} not installed: ${it.message}") }
                    .isSuccess
                if (!ok) File(staging, "tts").deleteRecursively()
            }
            onProgress(Progress(0.99f, "finishing"))

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
     * Add or replace just the voice of a language already on the phone.
     *
     * For every pack downloaded before voices were hosted — those understand speech and
     * cannot speak — and for the old int8 voices, which work but cost up to 25x the CPU.
     * The recogniser is left untouched, so this is a 28-50 MB fetch, not 240.
     */
    suspend fun downloadVoice(
        entry: CatalogueEntry,
        packDir: File,
        onProgress: (Progress) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            onProgress(Progress(0f, "voice"))
            installVoice(entry, packDir) { f -> onProgress(Progress(f * 0.98f, "voice")) }
            onProgress(Progress(1f, "ready"))
            Log.i(TAG, "voice v${entry.voiceVersion} installed for ${entry.code}")
            true
        }.getOrElse {
            Log.w(TAG, "voice for ${entry.code} did not install: ${it.message}")
            false
        }
    }

    /**
     * Fetch the voice archive into `<packDir>/tts` and declare it in `pack.json`.
     *
     * Staged beside the old voice and swapped in only once complete, so an interrupted
     * update leaves the previous voice working rather than none at all.
     */
    private suspend fun installVoice(entry: CatalogueEntry, packDir: File, onProgress: (Float) -> Unit) {
        val url = entry.voiceUrl ?: error("no voice offered for ${entry.code}")
        val archive = File(packDir, "tts.zip.partial")
        val staging = File(packDir, "tts.partial")
        try {
            fetch(url, archive, onProgress)
            if (staging.exists()) staging.deleteRecursively()
            unzip(archive, staging)

            val voice = JSONObject(File(staging, "voice.json").readText())
            // Check the vocabulary on the phone before the engine ever sees it: sherpa
            // aborts the process on a malformed tokens file rather than reporting it.
            val check = TokensFile.validate(
                File(staging, "tokens.txt"),
                requireSingleCharacter = !voice.has("dataDir"),
            )
            if (!check.isValid) error("voice rejected: ${check.error}")

            val live = File(packDir, "tts")
            if (live.exists()) live.deleteRecursively()
            if (!staging.renameTo(live)) error("could not move the voice into place")

            val descriptor = File(packDir, LanguagePack.PACK_FILE)
            val json = JSONObject(descriptor.readText())
            voice.put("voiceVersion", entry.voiceVersion)
            json.put("tts", voice)
            descriptor.writeText(json.toString(2))
        } finally {
            archive.delete()
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    /** Unpack [zip] into [dir], refusing any entry that would land outside it. */
    private fun unzip(zip: File, dir: File) {
        val root = dir.canonicalFile
        root.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val out = File(root, e.name).canonicalFile
                // A crafted "../" entry would otherwise write anywhere the app can.
                if (!out.path.startsWith(root.path + File.separator)) error("unsafe entry ${e.name}")
                if (e.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { z.copyTo(it, BUFFER) }
                }
            }
        }
    }

    /**
     * Write the `pack.json` that makes this directory a language.
     *
     * Generated rather than downloaded: the descriptor describes **this** app's pack
     * format, and fetching it from a model host would tie our format to a third party who
     * has never heard of us.
     *
     * Written without a `tts` block; [installVoice] adds one once the voice has arrived,
     * so a pack never declares a voice it does not have.
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

    /**
     * Download [url] into [into], resuming after a dropped connection.
     *
     * Retrying from zero is the wrong answer on the connections this app is for: a 188 MB
     * recogniser that drops at 90% would be fetched again in full, and on a link that
     * drops every few minutes it would never finish. So each retry asks only for the
     * bytes still missing (an HTTP Range request) and appends them.
     *
     * Found on the phone: a voice update died at "Software caused connection abort" — the
     * connection dropped mid-transfer, and the whole download was thrown away.
     *
     * A server that ignores the range answers 200 with the whole file, and the partial
     * copy is then discarded rather than appended to, so a resume can never corrupt a
     * model by splicing two different downloads together.
     */
    private suspend fun fetch(
        url: String,
        into: File,
        onProgress: (Float) -> Unit = {},
    ) {
        into.parentFile?.mkdirs()
        if (into.exists()) into.delete()
        var attempt = 0
        while (true) {
            attempt++
            try {
                fetchOnce(url, into, onProgress)
                return
            } catch (e: java.io.IOException) {
                // Only network failures are retried. Cancellation is not an IOException
                // and propagates, and an HTTP error status is not transient.
                if (attempt >= MAX_ATTEMPTS) throw e
                Log.w(TAG, "download interrupted at ${into.length()} B (${e.message}); " +
                    "resuming, attempt ${attempt + 1} of $MAX_ATTEMPTS")
                kotlinx.coroutines.delay(RETRY_BASE_MS * attempt)
            }
        }
    }

    private suspend fun fetchOnce(url: String, into: File, onProgress: (Float) -> Unit) {
        val have = if (into.exists()) into.length() else 0L
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            // Model hosts redirect to a CDN; without following it the download is a
            // 300-byte HTML page that fails much later as a corrupt model.
            instanceFollowRedirects = true
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) throw HttpStatusError("HTTP $code for $url")
            val resumed = code == HttpURLConnection.HTTP_PARTIAL && have > 0
            val start = if (resumed) have else 0L
            val length = connection.contentLengthLong
            val total = if (length > 0) start + length else -1L

            connection.inputStream.use { input ->
                java.io.FileOutputStream(into, resumed).use { output ->
                    val buffer = ByteArray(BUFFER)
                    var done = start
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
                    // A stream that ends early without an exception is still a short file.
                    if (total > 0 && done < total) {
                        throw java.io.EOFException("ended at $done of $total bytes")
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** A definite answer from the server. Not an IOException, so it is never retried. */
    private class HttpStatusError(message: String) : IllegalStateException(message)
}
