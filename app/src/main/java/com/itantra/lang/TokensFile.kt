package com.itantra.lang

import java.io.File

/**
 * Checks a `tokens.txt` before sherpa-onnx is allowed to see it.
 *
 * ## Why this exists
 *
 * The claim in the TRD — "a corrupt pack makes that one language unavailable, never
 * crashes the app" — was not true for voice models, and a real bug proved it. A Tamil
 * `tokens.txt` containing the single line `<unk> 58` made sherpa's character frontend
 * reject the file, and the process died in native code with no Java exception at all:
 * [LanguagePack.load] returning null and `runCatching` around the model construction are
 * both powerless against an abort below the JNI boundary.
 *
 * Reading the file here, in Kotlin, turns that class of fault back into an ordinary
 * error message. It cost four build-push-test cycles to find by guesswork; the same
 * fault is now named in milliseconds, before any native code runs.
 *
 * ## What it does and does not promise
 *
 * This is not a general guarantee that a pack cannot kill the process — a corrupt
 * `.onnx` graph still can, and nothing short of running the model in a separate process
 * would prevent that. It closes one specific, observed, entirely preventable hole: the
 * token vocabulary, which is plain text that we can check completely.
 *
 * The rules mirror sherpa's own readers (`offline-tts-character-frontend.cc:ReadTokens`):
 * each line is `<symbol> <id>`, split on the **last** space so a literal space works as
 * a symbol, and the id must be an integer. Duplicate ids are legitimate — MMS
 * vocabularies deliberately map several symbols to one id (`k 0`, `K 0`) — so they are
 * not an error.
 */
object TokensFile {

    /** The outcome of checking a tokens file. [error] is null when it is usable. */
    data class Result(val error: String?, val symbols: Int) {
        val isValid: Boolean get() = error == null
    }

    /**
     * Validate [file].
     *
     * @param requireSingleCharacter true for voices using the character frontend, where
     *   a longer symbol is certainly fatal. Piper voices are phoneme-based and read by a
     *   different sherpa frontend, so for those the rule is not enforced — a validator
     *   that rejected a working pack would be worse than the bug it prevents.
     */
    fun validate(file: File, requireSingleCharacter: Boolean): Result {
        if (!file.isFile) return Result("${file.name} is missing", 0)

        val lines = runCatching { file.readLines() }
            .getOrElse { return Result("${file.name} could not be read: ${it.message}", 0) }

        var symbols = 0
        lines.forEachIndexed { index, raw ->
            // Tolerate a CRLF file here even though our own exporter writes LF: a
            // stray carriage return is trivially strippable and not worth refusing a
            // pack over. It is only fatal if left attached to the id.
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) return@forEachIndexed

            val cut = line.lastIndexOf(' ')
            if (cut <= 0) {
                return Result(at(file, index, line, "expected \"<symbol> <id>\""), symbols)
            }
            val symbol = line.substring(0, cut)
            val id = line.substring(cut + 1)

            if (id.toIntOrNull() == null) {
                return Result(at(file, index, line, "\"$id\" is not a number"), symbols)
            }
            if (requireSingleCharacter && symbol.codePointCount(0, symbol.length) != 1) {
                return Result(
                    at(file, index, line, "symbol \"$symbol\" is ${symbol.length} characters, " +
                        "and this voice accepts only one"),
                    symbols,
                )
            }
            symbols++
        }

        if (symbols == 0) return Result("${file.name} contains no symbols", 0)
        return Result(null, symbols)
    }

    private fun at(file: File, index: Int, line: String, why: String) =
        "${file.name} line ${index + 1} ($line): $why"
}
