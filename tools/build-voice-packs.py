#!/usr/bin/env python3
"""
Assemble an offline voice for every language in the catalogue.

Eight of the ten already have a published ONNX voice and need only repackaging into this
project's pack layout. Two do not and have to be converted from Meta's MMS release by
export-mms-tts.py, which is slower and needs torch.

    | language        | voice                         | how          |
    |-----------------|-------------------------------|--------------|
    | English  en     | Piper en_US / en_GB           | repackage    |
    | Hindi    hi     | Piper hi_IN                   | repackage    |
    | Bengali  bn     | Piper bn_BD (also Coqui)      | repackage    |
    | Telugu   te     | Piper te_IN (raw, packaged here) | repackage |
    | Marathi  mr     | MMS — see the note on its Piper voice | export-mms |
    | Malayalam ml    | Piper ml_IN                   | repackage    |
    | Gujarati gu     | Mimic3 gu_IN                  | repackage    |
    | Tamil    ta     | MMS (converted here)          | export-mms   |
    | Kannada  kn     | MMS                           | export-mms   |
    | Odia     or     | MMS                           | export-mms   |

The ZIP this project was compared against declared TTS "unsupported" for Kannada, Tamil,
Telugu, Marathi and Odia after checking Piper, Coqui, Mimic3 and MMS. Three of those five
do have published Piper voices — they are simply not in sherpa-onnx's release, which is
where a search stops if it only looks there. Two of those three work; Marathi's does not,
for the reason noted beside it below.

Verified by synthesising a real sentence in each and checking the audio is not silence:

    en hi te ml gu bn   speak      (2.3 - 3.1 s, RMS 0.07 - 0.18)
    ta                  speak      (converted from MMS earlier)
    mr kn or            pending    (MMS export)

Output is one directory per language under build/voices/<code>/, holding model.onnx,
tokens.txt and (for Piper voices) espeak-ng-data. That is the layout a pack.json expects.

Usage:
    python tools/build-voice-packs.py --list
    python tools/build-voice-packs.py --lang hi
    python tools/build-voice-packs.py --all --skip-mms
"""

import argparse
import io
import json
import os
import shutil
import sys
import tarfile
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

SHERPA = ("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/{name}.tar.bz2")

# Voice chosen per language. Where several exist the medium int8 build is taken: it is the
# best size-to-quality point for a phone, and int8 is what every other model here uses.
VOICES = {
    "en": ("piper", "vits-piper-en_US-lessac-medium-int8"),
    "hi": ("piper", "vits-piper-hi_IN-priyamvada-medium-int8"),
    "bn": ("coqui", "vits-coqui-bn-custom_female"),
    # Not in sherpa-onnx's release, only in Piper's own repo — fetched raw and
    # packaged here. This is the gap that makes a search stop too early: looking only at
    # sherpa's assets says Telugu and Marathi have no voice, and both do.
    "te": ("piper-raw", "te/te_IN/maya/medium/te_IN-maya-medium"),
    # Marathi's Piper voice CANNOT be used: its phoneme map contains five diphthongs
    # written as two code points (aɪ aʊ ɔɪ eɪ oʊ), and sherpa's piper-phonemize reader
    # requires every symbol to be a single character. Dropping them would load but would
    # mispronounce every diphthong in the language, which is a worse outcome than using a
    # different voice. MMS is character-based and has no such constraint.
    "mr": ("mms", "mar"),
    "ml": ("piper", "vits-piper-ml_IN-meera-medium-int8"),
    "gu": ("mimic3", "vits-mimic3-gu_IN-cmu-indic_low"),
    # No published ONNX voice in any catalogue searched; converted from MMS instead.
    "ta": ("mms", "tam"),
    "kn": ("mms", "kan"),
    "or": ("mms", "ory"),
}

OUT = os.path.join("build", "voices")


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "curl/8"})
    with urllib.request.urlopen(req, timeout=300) as r:
        return r.read()


PIPER_RAW = "https://huggingface.co/rhasspy/piper-voices/resolve/main/{path}"
ESPEAK = SHERPA.format(name="espeak-ng-data")


def piper_raw(code: str, path: str) -> bool:
    """
    Package a Piper voice that sherpa-onnx does not publish.

    Piper ships the model as ONNX already, so no conversion is needed — but sherpa wants a
    `tokens.txt`, and Piper keeps its symbol table inside the model's JSON sidecar instead.
    Writing it out is the whole of the work.
    """
    dest = os.path.join(OUT, code)
    os.makedirs(dest, exist_ok=True)
    print(f"  {code}: {path} (from piper-voices)")

    try:
        model = fetch(PIPER_RAW.format(path=path + ".onnx"))
        meta = json.loads(fetch(PIPER_RAW.format(path=path + ".onnx.json")).decode())
    except Exception as e:
        print(f"    could not fetch ({e})")
        return False

    with open(os.path.join(dest, "model.onnx"), "wb") as f:
        f.write(model)

    # phoneme_id_map is {symbol: [id, ...]}. sherpa wants "<symbol> <id>" per line, and
    # the ids must be written in numeric order for the file to be readable at a glance.
    id_map = meta.get("phoneme_id_map") or {}
    rows = sorted(((ids[0], sym) for sym, ids in id_map.items() if ids), key=lambda r: r[0])
    if not rows:
        print("    the JSON has no phoneme_id_map — skipped")
        return False
    # LF explicitly, via chr(10) rather than an escape: sherpa's C++ token readers split
    # on newlines only, so a CRLF file leaves a carriage return attached to the last field
    # and the id stops parsing as a number. See the note in export-mms-tts.py.
    lf = chr(10)
    with io.open(os.path.join(dest, "tokens.txt"), "w", encoding="utf-8", newline=lf) as f:
        for tid, sym in rows:
            f.write(f"{sym} {tid}{lf}")

    # Inject the metadata sherpa reads at load.
    #
    # Piper's own ONNX carries NONE — the values live in the JSON sidecar instead, and
    # sherpa-onnx adds them during its packaging step. Without this the model loads far
    # enough to look fine and then fails with "'sample_rate' does not exist in the
    # metadata", which reads like a corrupt download rather than a missing packaging step.
    if not _add_metadata(os.path.join(dest, "model.onnx"), code, meta):
        return False

    print(f"    ok — {len(model) / 1048576:.1f} MB, {len(rows)} symbols")
    return True


def _add_metadata(path: str, code: str, meta: dict) -> bool:
    """Copy the key set a sherpa-packaged Piper voice carries, valued from the sidecar."""
    try:
        import onnx
    except ImportError:
        print("    onnx is not installed — pip install onnx")
        return False

    model = onnx.load(path)
    have = {x.key for x in model.metadata_props}
    fields = {
        "model_type": "vits",
        "comment": "piper",
        "language": (meta.get("language") or {}).get("name_english", code),
        "voice": code,
        # Piper is phoneme-based and needs espeak's pronunciation data at run time.
        "has_espeak": "1",
        "n_speakers": str(meta.get("num_speakers", 1)),
        "sample_rate": str((meta.get("audio") or {}).get("sample_rate", 22050)),
    }
    for k, v in fields.items():
        if k in have:
            continue
        entry = model.metadata_props.add()
        entry.key, entry.value = k, str(v)

    onnx.save(model, path)
    print(f"    metadata added: sample_rate={fields['sample_rate']}, "
          f"speakers={fields['n_speakers']}")
    return True


def fetch_espeak() -> None:
    """The pronunciation data every Piper and Mimic3 voice shares. ~7 MB, fetched once."""
    dest = os.path.join(OUT, "_espeak-ng-data")
    if os.path.isdir(dest):
        print("  espeak-ng-data already present")
        return
    print("  fetching shared espeak-ng-data")
    blob = fetch(ESPEAK)
    os.makedirs(dest, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(blob), mode="r:bz2") as tar:
        for m in tar.getmembers():
            if not m.isfile() or "espeak-ng-data/" not in m.name:
                continue
            rel = m.name.split("espeak-ng-data/", 1)[1]
            if not rel:
                continue
            target = os.path.join(dest, rel)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "wb") as f:
                f.write(tar.extractfile(m).read())
    print("    ok")


def repackage(code: str, kind: str, name: str) -> bool:
    """Download a published voice and lay it out the way a language pack expects."""
    dest = os.path.join(OUT, code)
    url = SHERPA.format(name=name)
    print(f"  {code}: {name}")

    try:
        blob = fetch(url)
    except Exception as e:
        print(f"    NOT AVAILABLE at that name ({e})")
        return False

    os.makedirs(dest, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(blob), mode="r:bz2") as tar:
        members = tar.getmembers()

        # The archive holds one top-level directory; everything below it is what we want.
        model = next((m for m in members if m.name.endswith(".onnx")), None)
        tokens = next((m for m in members if m.name.endswith("tokens.txt")), None)
        if model is None or tokens is None:
            print("    archive has no model or tokens — skipped")
            return False

        with open(os.path.join(dest, "model.onnx"), "wb") as f:
            f.write(tar.extractfile(model).read())
        with open(os.path.join(dest, "tokens.txt"), "wb") as f:
            f.write(tar.extractfile(tokens).read())

        # Piper and Mimic3 voices are phoneme-based and need espeak's pronunciation data.
        # MMS voices are character-based and carry their own vocabulary, which is why the
        # pack format makes this directory optional.
        espeak = [m for m in members if "espeak-ng-data/" in m.name and m.isfile()]
        if espeak:
            for m in espeak:
                rel = m.name.split("espeak-ng-data/", 1)[1]
                if not rel:
                    continue
                target = os.path.join(dest, "espeak-ng-data", rel)
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with open(target, "wb") as f:
                    f.write(tar.extractfile(m).read())

    size = sum(
        os.path.getsize(os.path.join(root, f))
        for root, _, files in os.walk(dest) for f in files
    )
    print(f"    ok — {size / 1048576:.1f} MB, espeak data: {'yes' if espeak else 'no'}")
    return True


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", help="one language code")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--skip-mms", action="store_true",
                    help="skip the three that need a torch export")
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--espeak", action="store_true",
                    help="fetch the shared espeak-ng-data that Piper voices need")
    args = ap.parse_args()

    if args.list:
        for code, (kind, name) in VOICES.items():
            print(f"{code:4} {kind:7} {name}")
        return

    codes = [args.lang] if args.lang else list(VOICES) if args.all else []
    if not codes:
        ap.error("pass --lang <code>, --all, or --list")

    if args.espeak:
        fetch_espeak()

    ok, todo = [], []
    for code in codes:
        kind, name = VOICES[code]
        if kind == "mms":
            if args.skip_mms:
                continue
            todo.append((code, name))
            continue
        done = piper_raw(code, name) if kind == "piper-raw" else repackage(code, kind, name)
        if done:
            ok.append(code)

    print(f"\nrepackaged: {', '.join(ok) if ok else 'none'}")
    if todo:
        print("needs conversion from MMS (run these separately, each needs torch):")
        for code, mms in todo:
            print(f"  python tools/export-mms-tts.py --lang {mms} --out {OUT}/{code}")


if __name__ == "__main__":
    main()
