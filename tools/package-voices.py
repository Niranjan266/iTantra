#!/usr/bin/env python3
"""
Build one downloadable voice archive per language: build/voice-release/tts-<code>.zip

    python tools/package-voices.py            # all ten
    python tools/package-voices.py ta mr      # some

Each archive holds exactly what a pack's `tts/` directory needs:

    model.onnx        the voice, weights stored half-size (see shrink-voice.py)
    tokens.txt        LF line endings, validated
    espeak-ng-data/   only for phoneme voices, trimmed to this language's dictionary
    voice.json        how to load it — merged into pack.json by the app on install

## Choice of voice, by measured CPU cost (one thread, seconds of CPU per second of speech)

    en hi ml   Piper medium          0.08 - 0.09   (was 0.46 as int8)
    te         Piper medium (raw)    0.10
    gu         Mimic3                0.08
    ta mr kn or bn   Meta MMS        0.49 - 0.66   (Tamil was 15.7 as int8)

Bengali moved from Coqui (1.17, 109 MB) to MMS (0.49, 55 MB): less than half the CPU at
half the size. Every int8 build was dropped — see shrink-voice.py for why int8 is the
wrong trade on a phone CPU.

MMS voices are CC-BY-NC 4.0 (non-commercial). Piper/Mimic3 voices carry their own
licences, recorded in the model cards upstream.
"""
import importlib.util, io, json, os, shutil, sys, tempfile, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
B = os.path.join(ROOT, "build")
ESPEAK = os.path.join(B, "voices", "_espeak-ng-data")
OUT = os.path.join(B, "voice-release")

# code: (model, tokens, espeak dictionary or None, sample rate)
VOICES = {
    "en": ("cand/vits-piper-en_US-lessac-medium/en_US-lessac-medium.onnx",
           "cand/vits-piper-en_US-lessac-medium/tokens.txt", "en", 22050),
    "hi": ("cand/vits-piper-hi_IN-priyamvada-medium/hi_IN-priyamvada-medium.onnx",
           "cand/vits-piper-hi_IN-priyamvada-medium/tokens.txt", "hi", 22050),
    "ml": ("cand/vits-piper-ml_IN-meera-medium/ml_IN-meera-medium.onnx",
           "cand/vits-piper-ml_IN-meera-medium/tokens.txt", "ml", 22050),
    "te": ("voices/te/model.onnx", "voices/te/tokens.txt", "te", 22050),
    "gu": ("voices/gu/model.onnx", "voices/gu/tokens.txt", "gu", 22050),
    "ta": ("mms/ta/model.onnx", "mms/ta/tokens.txt", None, 16000),
    "mr": ("mms/mr/model.onnx", "mms/mr/tokens.txt", None, 16000),
    "kn": ("mms/kn/model.onnx", "mms/kn/tokens.txt", None, 16000),
    "or": ("mms/or/model.onnx", "mms/or/tokens.txt", None, 16000),
    "bn": ("mms/bn/model.onnx", "mms/bn/tokens.txt", None, 16000),
}

# Two threads: enough that a sentence is ready before the listener notices a gap, few
# enough that synthesis does not take every core from the recogniser and the radio.
THREADS = 2

spec = importlib.util.spec_from_file_location("shrink", os.path.join(ROOT, "tools", "shrink-voice.py"))
shrink = importlib.util.module_from_spec(spec); spec.loader.exec_module(shrink)


def trimmed_espeak(dst: str, lang: str) -> None:
    """Everything espeak needs except the other ~120 languages' dictionaries (18 MB -> ~1 MB)."""
    for name in os.listdir(ESPEAK):
        src = os.path.join(ESPEAK, name)
        if name.endswith("_dict") and name != f"{lang}_dict":
            continue
        (shutil.copytree if os.path.isdir(src) else shutil.copy2)(src, os.path.join(dst, name))
    if not os.path.exists(os.path.join(dst, f"{lang}_dict")):
        raise SystemExit(f"espeak has no dictionary for {lang}")


def build(code: str) -> None:
    model, tokens, dictionary, rate = VOICES[code]
    with tempfile.TemporaryDirectory() as tmp:
        shrink.shrink(os.path.join(B, model), os.path.join(tmp, "model.onnx"))
        # LF only: a CR left on each line stops sherpa parsing the id on Android.
        text = io.open(os.path.join(B, tokens), encoding="utf-8").read().replace("\r", "")
        io.open(os.path.join(tmp, "tokens.txt"), "w", encoding="utf-8", newline="\n").write(text)
        voice = {"model": "tts/model.onnx", "tokens": "tts/tokens.txt",
                 "sampleRate": rate, "speakerId": 0, "speed": 1.0, "numThreads": THREADS}
        if dictionary:
            os.makedirs(os.path.join(tmp, "espeak-ng-data"))
            trimmed_espeak(os.path.join(tmp, "espeak-ng-data"), dictionary)
            voice["dataDir"] = "tts/espeak-ng-data"
        io.open(os.path.join(tmp, "voice.json"), "w", encoding="utf-8").write(json.dumps(voice, indent=2))

        os.makedirs(OUT, exist_ok=True)
        dst = os.path.join(OUT, f"tts-{code}.zip")
        with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            for base, _, files in os.walk(tmp):
                for f in files:
                    full = os.path.join(base, f)
                    z.write(full, os.path.relpath(full, tmp).replace(os.sep, "/"))
        print(f"{code}: {os.path.getsize(dst) / 1048576:.1f} MB -> {dst}")


if __name__ == "__main__":
    for c in (sys.argv[1:] or VOICES):
        build(c)
