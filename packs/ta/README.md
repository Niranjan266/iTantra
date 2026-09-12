# Tamil language pack — the parts that are source

The model files are **not** here and are not in git: `asr-nemo/model.int8.onnx` alone is
188.4 MB, which exceeds GitHub's 100 MB hard limit per file, and the pack totals ~226 MB. They are
reproducible, so committing them would trade a fast clone for nothing.

What *is* here is everything a human wrote, which is not reproducible:

| File | Why it is source |
|---|---|
| `pack.json` | declares the model types, paths and the self-test sentence |
| `phrases.txt` | the phrase codebook — **a wire contract**, see below |

## Rebuilding the full pack

```bash
# 1a. Recogniser — AI4Bharat IndicConformer, Apache-2.0, already ONNX for sherpa.
#     tokens.txt is SHARED across all ten Indic languages, so a second Indic language
#     needs only its own model.int8.onnx.
B=https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main
mkdir -p build/packs/ta/asr-nemo
curl -L "$B/ta/model.int8.onnx" -o build/packs/ta/asr-nemo/model.int8.onnx   # 188 MB
curl -L "$B/tokens.txt"         -o build/packs/ta/asr-nemo/tokens.txt        # 66 KB

# 1b. Voice — no ready-made Tamil voice exists, so it is converted here.
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
