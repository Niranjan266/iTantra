# Tamil language pack — the parts that are source

The model files are **not** here and are not in git: `asr/decoder.onnx` alone is 124.6 MB,
which exceeds GitHub's 100 MB hard limit per file, and the pack totals 190 MB. They are
reproducible, so committing them would trade a fast clone for nothing.

What *is* here is everything a human wrote, which is not reproducible:

| File | Why it is source |
|---|---|
| `pack.json` | declares the model types, paths and the self-test sentence |
| `phrases.txt` | the phrase codebook — **a wire contract**, see below |

## Rebuilding the full pack

```bash
# 1. Fetch / convert the models into build/packs/ta/
powershell -ExecutionPolicy Bypass -File tools/fetch-models.ps1
python tools/export-mms-tts.py --lang tam --out build/packs/ta/tts

# 2. Copy these source files over the top
cp packs/ta/pack.json packs/ta/phrases.txt build/packs/ta/

# 3. Install on a phone — no rebuild, no reinstall of the app
adb push build/packs/ta /sdcard/Android/data/com.itantra/files/languages/
```

## `phrases.txt` is a wire contract

A phrase's **line number is the number that travels on the link**. Two phones holding
different files will exchange confident nonsense, which is worse than failing. So once the
file has shipped to more than one device it is **append-only**: never reorder, never
delete, never change an existing line's meaning. Retiring a phrase means leaving it in
place and stopping using it.

The Tamil list is still a **draft pending review by a Tamil speaker** and is therefore not
yet frozen — but both phones must be updated together whenever it changes.
