# iTantra

Offline voice-to-voice communication across links too narrow to carry audio.

SIH Problem Statement **26173** — ISRO / Department of Space.

A speaker talks; a listener hears a voice. Neither sees text. What actually crosses the link is a
~75-byte encoded packet instead of a ~7,500-byte compressed voice note — roughly 60× smaller, which
is the difference between a message that arrives and one that does not.

## Download

**[Latest release — install the APK](https://github.com/Niranjan266/iTantra/releases/latest)**

Take `iTantra-v0.1.0-arm64-v8a.apk` unless that refuses to install, in which case take the
universal build. Works immediately on install: no account, no setup, no network. Android
will warn about installing outside the Play Store — these are debug-signed builds.

The APK is a **release asset, not a file in this repository**. 99.5 MB in git would be
cloned by everyone for ever; the Tamil recogniser at 124.6 MB could not be committed at
all, being past GitHub's 100 MB per-file limit. Nothing binary is tracked here — it is all
reproducible, which is what keeps a clone a few hundred kilobytes.

```bash
git clone https://github.com/Niranjan266/iTantra.git
cd iTantra
powershell -ExecutionPolicy Bypass -File tools/fetch-models.ps1   # once, ~190 MB
./gradlew testDebugUnitTest assembleDebug
```

## Documents

| Document | What it covers |
|---|---|
| [PRD.md](docs/PRD.md) | What we are building, for whom, and how it will be scored |
| [TRD.md](docs/TRD.md) | Architecture, the ITP-1 wire format, latency budget, test plan |
| [IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) | Eight phases, each with a gate that must be observed before the next starts |

## Status

| Phase | State |
|---|---|
| 0 — buildable skeleton | ✅ done |
| 1 — the frozen wire format | ✅ done, verified on device |
| 2 — Bluetooth between two phones | ✅ **Gate 2 passed** — 62 B delivered vivo → Moto over RFCOMM |
| 3 — microphone and playback | ✅ capture + recognition verified by on-device self-test |
| 4 — alerting | ✅ volume override and restore verified on device |
| 5 — real speech models | ✅ English verified end to end; Tamil added (see below) |
| 6 — languages as data | ✅ **proven live** — Tamil added with no rebuild, no reinstall |
| 7 — narrow-link proof | ✅ built and unit-tested; **needs on-device run** |
| 8 — measurement | ✅ WER, prosody, percentiles, CSV export — all unit-tested |

**226 unit tests, 0 failures.**

### Languages

| Language | ASR | TTS | Ships as |
|---|---|---|---|
| English | streaming Zipformer 20M (int8) | Piper VITS (int8) | bundled in the APK, 61 MB |
| தமிழ் Tamil | **IndicConformer CTC (int8)**, Apache-2.0 | MMS VITS, converted + quantised here | drop-in pack, ~226 MB |

#### Tamil recognition: the model matters more than the pipeline

Tamil first ran on multilingual Whisper, because it was the only thing that had Tamil at
all. It is bad at it. Both figures below are the **same audio file** — our own synthesised
self-test sentence — through the same wrapper:

| Recogniser | Heard | WER |
|---|---|---|
| Whisper base, multilingual | `விளம் வியருகிறது ப` | ~100% |
| **AI4Bharat IndicConformer** | `வெள்ளம் உயர்கிறது படகுகளை அனுப்புங்கள்` | **0%** |

Exact transcription against near-total failure. A model trained on one language beats one
trained on ninety-nine — unsurprising, and worth having measured rather than assumed.

Two side benefits. It is **Apache-2.0**, so this half of the Tamil pipeline carries no
non-commercial restriction (the MMS voice still does). And its 66 KB vocabulary is
**shared across all ten AI4Bharat Indic languages**, so the next Indic language costs only
its own model file.

It needed a third recogniser family in the app — one CTC graph rather than an
encoder/decoder/joiner split. That is a data change, as intended: the pack declares
`"type": "nemo_ctc"` and the factory picks the engine. Nothing in Kotlin names Tamil.

#### Tamil voice: still no ready-made model anywhere

The recogniser was findable. The **voice was not**, and that part still had to be built.
Checked, not assumed:

| Catalogue | Entries | Tamil voice |
|---|---|---|
| Piper voices (`rhasspy/piper-voices`) | 56 languages | ✗ (has `hi`, `ml`, `te`, `mr`, `bn`, `ur`) |
| sherpa-onnx TTS release assets | 643 | ✗ |
| MMS→ONNX conversions (`csukuangfj/vits-mms-*`) | 8 languages | ✗ |

So the voice is converted here from `facebook/mms-tts-tam` by `tools/export-mms-tts.py`,
then quantised from 109 MB to 36.8 MB.

**A correction worth recording.** An earlier version of this file claimed the community
ONNX conversions of the IndicConformer family "cover eight Indian languages but not
Tamil", and Tamil recognition ran on Whisper for that reason. That was **wrong** — a
converted Tamil IndicConformer does exist, under Apache-2.0, and switching to it took
Tamil from roughly 100% word error to 0% on the test sentence. The claim was stated with
more confidence than the search behind it justified, and a whole language ran on the wrong
model because of it.

**Licence note:** MMS-TTS is CC-BY-NC 4.0 — non-commercial use only. Fine for a
competition entry; it must be stated, and a commercial deployment would need a different
Tamil voice. The recogniser is Apache-2.0 and carries no such restriction.

#### One line of text, four wasted build cycles

Worth recording, because the lesson generalises. The converted Tamil voice killed the app
on load — a native abort, no Java exception, a tombstone naming only sherpa's internals.
Four rounds of fixes went into the *model*: input names, speaker count, scale parameters
traced away as constants, line endings. Each round meant a full build-push-test cycle and
each one failed identically.

The model was never the problem. Running the same files through the **desktop** build of
sherpa-onnx printed the cause in one second:

```
offline-tts-character-frontend.cc:ReadTokens:66
Error when reading tokens at Line <unk> 58. size: 5
```

The vocabulary contained `<unk> 58`. The character frontend requires every symbol to be
exactly **one** character, and `<unk>` is five. Deleting that single line made Tamil
speak. The rule is now enforced in the exporter and, independently, in the app — see
below.

The generalisable part: **when a native library aborts on Android, reproduce it on the
desktop before touching the artefact.** The same library prints the assertion that the
Android build swallows.

### A bad pack can no longer take the app with it

The TRD claimed a corrupt pack would cost one language and never crash the app. That was
not true, and this bug proved it: `LanguagePack.load` returning null and `runCatching`
around model construction are both powerless against an abort below the JNI boundary.

`TokensFile` now reads the vocabulary in Kotlin, before any native code runs, and rejects
a malformed one with a message that names the file and line. This is a real fix for one
specific, observed, completely preventable hole — **not** a general guarantee: a corrupt
`.onnx` graph can still abort the process, and only running the model in a separate
process would prevent that. `TokensFileTest` pins the behaviour, including the exact line
that caused the original crash.

### Adding a language, demonstrated

Tamil is installed by copying a folder — no rebuild, no reinstall:

```bash
adb push build/packs/ta /sdcard/Android/data/com.itantra/files/languages/
```

On the next launch the app logs `packs: en(0), ta(6)` and Tamil appears in the picker.
The pack declares its own model paths, model type, and even its own self-test sentence,
so nothing in the Kotlin source names a language.

### The narrow-link claim, as arithmetic

`LinkBudget` computes airtime for a real measured packet against published codec
bitrates. Every figure below is checkable with a calculator, and the tests pin them.

For one 3-second utterance over a **300 bps** bearer (LoRa SF12 / satellite short-burst):

| Representation | Size | Airtime |
|---|---|---|
| **iTantra packet** | **75 B** | **2.0 s** ✓ keeps up |
| Opus voice at 12 kbps | 4,500 B | 2 min 00 s ✗ |
| Raw 16 kHz PCM | 96,000 B | 42 min 40 s ✗ |

The bearer is selectable in-app, so this can be run live rather than asserted.

### Phrase codebook — the same sentence in 16 bytes

Disaster traffic is enormously repetitive. A flood response is mostly a few hundred
sentences about water, boats, roads, medicine and people. If both ends already hold that
sentence in a numbered list, the message is a **12-bit index** and the header becomes the
expensive part:

| Representation of *"Flood water is rising near the school, send boats"* | Size | @ 300 bps |
|---|---|---|
| Opus voice at 12 kbps | 4,500 B | 2 min 00 s |
| Spelled out as an ITP-1 packet | 62 B | 1.7 s |
| **As a phrase reference** | **16 B** | **0.43 s** |

11-byte header + 3-byte reference + 2-byte CRC. `PhraseCodebookTest` measures that 16
through the real codec rather than asserting it in prose.

**What it does not do.** A codebook only helps for phrases that are in it; free speech
costs full price, and a miss is the ordinary case rather than a failure. So the encoding
used is named per message in the CSV (`sent, phrase 33` vs `sent, text`) — an averaged
"compression ratio" over a mix of the two would credit the codebook for bytes it never
saved.

**How it fits a frozen wire format.** It does not change the format. The symbol table
reserved slots 6–15 for control from version 1, and `PHRASE_REF` takes slot 6; the flags
nibble in the header has no spare bit, so that reservation is the only thing that made
this addable without a new protocol version. An older receiver sees an unassigned control
symbol and skips it — it drops the phrase rather than mis-speaking it.

**Both ends must hold the identical list.** A phrase id is an index, not words: two
devices with different lists would exchange confident nonsense, which is worse than
failing. The list is therefore **append-only** and fingerprinted (`PhraseCodebook.fingerprint`),
and a reference that cannot be resolved shows as `[phrase 900 — not in this device's
codebook]` on screen and as silence to the speaker.

English ships 144 phrases. Tamil ships 32 as a **draft that a Tamil speaker must review
before it is frozen** — a wrong phrase in a distress codebook is worse than a missing one,
and a phrase also only compresses if it matches what the recogniser actually returns.

### Measured on a Motorola Edge 60 Pro

| | |
|---|---|
| Language pack install (first run) | 190 ms |
| Speech models loaded and ready | **2.8 s** |
| Installed APK, arm64 | **99 MB** (universal 120 MB) |
| Audio frames dropped during capture | **0** |

APKs are split per CPU architecture: a phone installs only the native libraries it can
execute, which is ~21 MB off the size the efficiency metric measures.

### Works with no setup and no network

The English pack ships **inside the APK** and is copied into app storage on first launch
(measured: 201 ms). Install the APK on any phone and it works immediately — no `adb push`,
no download, no account, and the app contacts **no server of any kind** — there is no web address anywhere in the
source, no account and no DNS lookup.

**A correction.** This used to say the app declared *no `INTERNET` permission at all*,
which was true and was a structural guarantee rather than a promise. Adding the Wi-Fi
bearer ended that: Android requires `INTERNET` for **any** socket, including the purely
local multicast this uses, and there is no local-only alternative. The datagrams carry a
TTL of 1 to a `239.x` administratively-scoped group, so a router will not forward them off
the subnet — but that is reasoning, where the old claim was a fact. The **BLE bearer still
needs no such permission**, and remains the choice when that distinction matters.

Adding a language is still a file-copy: drop a folder beside the first one. The bundled
pack takes exactly the same code path as one added later — it is a convenience, not a
special case.

### The tests

| Suite | Tests | Covers |
|---|---|---|
| `PhraseCodebookTest` | 28 | phrase compression, and that speech and screen agree |
| `FloodRelayTest` | 24 | mesh relay, hop limit, duplicate suppression, repetition |
| `PacketCodecTest` | 23 | the frozen ITP-1 byte layout |
| `LanguagePackTest` | 20 | reading a pack of any recogniser family, refusing a malformed one |
| `ProsodyExtractorTest` | 17 | pitch, rate and energy in three bytes |
| `WordErrorRateTest` | 17 | WER with error attribution, NFC-normalised |
| `VadGateTest` | 17 | speech detection and endpointing |
| `FrameReaderTest` | 15 | reassembling frames from a byte stream |
| `TokensFileTest` | 14 | rejecting a vocabulary that would abort the process |
| `ThrottleTest` | 14 | airtime at a simulated bearer rate |
| `MetricsTest` | 13 | latency percentiles and CSV export |
| `ToneTest` | 10 | placeholder audio rendering |
| `AlertRulesTest` | 9 | what each urgency level may override |
| `CrcTest` | 5 | checksums, against published check values |
| **Total** | **226** | 0 failures |

### Gate 2 — passed on two paired handsets (12 Sep 2026)

vivo Y400 Pro 5G → Motorola Edge 60 Pro, Bluetooth RFCOMM, both in the app:

```
vivo : iTantra.BT: connected to JIO_motorola edge 60 pro as Join
moto : iTantra.BT: connected to vivo Y400 Pro 5G as Host
moto : Received (62 bytes) "Flood water is rising near the school, send boats"
       English · ROUTINE · seq 0      Sent 1 · Received 1 · Dropped 0
```

Throttled to 300 bps on that same live link, the app reported:

| | Airtime |
|---|---|
| **iTantra 62 B** | **1.7 s** |
| Opus voice | 1 min 01 s ✗ cannot keep up |
| Raw audio | 21 min 54 s |

### Phase 2 — verified on a real handset

| Check | Result |
|---|---|
| Permission flow (Host disabled until granted) | ✅ |
| Paired-device list, filtered to plausible peers | ✅ 18 devices → 2 shown |
| Host opens a real RFCOMM server socket | ✅ OS reports `STATE_LISTENING … RFCOMM iTantra`, channel 5 |
| Transport switch Loopback → Host → Loopback | ✅ 1 sent, 1 received, 0 dropped |

### Phase 4 — verified on a real handset

The problem statement asks for alert messages "announced at highest volume
non-interruptible". That means overriding settings the user chose, so it was verified in
both directions:

| Check | Result |
|---|---|
| DISTRESS raises the alarm volume | ✅ 3/7 → **7/7** |
| The user's volume is restored afterwards | ✅ back to 3/7 |
| ROUTINE leaves alarm volume alone | ✅ unchanged |
| ROUTINE leaves media volume alone | ✅ unchanged |

### What is still open

| Item | State | Needs |
|---|---|---|
| **Tamil voice on the phone** | synthesises correctly on the desktop — 2.66 s of audio, peak 0.70, RMS 0.15 for the self-test phrase | a connected handset. Verified against desktop sherpa-onnx **1.13.8**; the app bundles **1.13.6**. The tokens reader is the same in both, but that is reasoning, not a measurement |
| **Tamil recognition quality** | Whisper loads and runs for `ta` | a Tamil speaker. Accuracy is unmeasured — the self-test needs TTS to generate its input, so it can now be run, but WER against a human reading is the real check |
| **Gate 3** — hear your own voice | capture proven (0 dropped frames) | a person to hold the button, speak, and listen. That the voice *sounds right* cannot be checked over adb |
| True mouth-to-ear latency | round-trip on one device is measured | a shared clock across two handsets. The figure shown is labelled "Round trip" because that is what it is |
| Prosody `rate` | saturates | it is fed a character count where a phoneme count is meant |

## Build

Requires the Android SDK and JDK 21. The JDK path is pinned in `gradle.properties` because a
system JDK newer than 21 is not supported by the Android build tools.

**First time only** — fetch the speech runtime and models (~190 MB download, once):

```bash
powershell -ExecutionPolicy Bypass -File tools/fetch-models.ps1
```

Then:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Install on a connected phone:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run just the format tests:

```bash
./gradlew testDebugUnitTest --tests "com.itantra.codec.*"
```

## Layout

```
docs/                  PRD, TRD, implementation plan, measurements
app/src/main/java/com/itantra/
  codec/               the ITP-1 wire format — the frozen contract
  transport/           the seam: loopback today, Bluetooth and Wi-Fi Direct next
  session/             the state machine
  telemetry/           latency tracking and CSV export
  ui/                  PTT screen and the live metrics readout
app/src/test/          the tests that pin the wire format
```

## The one rule

`app/src/main/java/com/itantra/codec/` defines a byte layout that devices in the field will speak to
each other for years. Once it ships, it does not change — new capability goes in a new protocol
version, never by redefining an existing byte. The tests in `PacketCodecTest` exist to make an
accidental change to that contract impossible to do quietly.
