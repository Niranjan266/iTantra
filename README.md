# iTantra

**Offline voice messaging for links too narrow to carry audio.** Speak into one phone, hear a voice come out of another, with no internet, no SIM and no server in between.

[Download the APK](https://github.com/Niranjan266/iTantra/releases/latest) · [Case study](https://www.niranjand.in/projects/itantra) · [Technical design (TRD)](docs/TRD.md) · [Product requirements (PRD)](docs/PRD.md)

---

## What it is

When the network goes down after a flood or a cyclone, the links that still work, such as long-range radio, satellite short-burst or a phone held up to a walkie-talkie, are far too slow to carry a voice note. iTantra gets around that by sending the *meaning* of what you said instead of the sound. Your phone recognises your speech, packs it into a message of about 75 bytes, and the phone at the other end speaks it again in a natural voice. Neither person has to read or type anything.

It runs entirely on the phone, works in English and nine Indian languages, and can even deliver a known emergency phrase in the listener's own language.

## Features

- **Talk, don't type.** Hold the button, speak, and the other phone says it aloud. Neither person ever sees text unless they want to.
- **About 60 times smaller than a voice note.** A spoken sentence travels as a ~75-byte packet instead of a ~7,500-byte compressed audio clip.
- **Works fully offline.** Speech recognition and speech synthesis both run on the device. In the field the app talks to no server at all.
- **Ten languages.** English ships inside the APK; Hindi, Tamil, Telugu, Malayalam, Gujarati, Marathi, Kannada, Odia and Bengali are downloadable language packs.
- **Cross-language emergency phrases.** 144 common emergency phrases are numbered identically in every language, so a Hindi speaker's "we need help now" arrives in Tamil, spoken by a Tamil voice, for 16 bytes and no translation model.
- **Many ways to reach the other phone.** Wi-Fi broadcast, Bluetooth LE broadcast with phone-to-phone relaying, Bluetooth pairing, Wi-Fi Direct, and a sound link that plays the message as tones through a speaker (it fits through a walkie-talkie).
- **Automatic link by default.** Wi-Fi and Bluetooth broadcast run together, so two iTantra phones find each other on whichever one works.
- **Alerts that cannot be missed.** A distress message raises the alarm volume to maximum and puts the user's volume back afterwards.
- **Built-in measurement.** Word error rate, latency percentiles, bytes on the wire and simulated narrow-link airtime, with CSV export.

## How it works

### The signal path

The only thing that ever crosses the link is a packet. Everything on the sending side and everything on the receiving side are independent programs that share nothing except the byte format.

```
  SPEAKER'S PHONE                                     LISTENER'S PHONE
  ───────────────                                     ────────────────
  Microphone (16 kHz PCM)                             Transport (receive)
     │                                                   │  bytes
  VadGate         speech detection + endpointing      FrameReader       resync on magic byte
     │                                                   │
  AsrEngine       on-device recognition               PacketCodec.decode  CRC-8 + CRC-16 checks
     │  text                                             │  Packet
  PhraseCodebook  known phrase? → 12-bit id           Translator        phrase id → listener's language
  ProsodyExtractor pitch / rate / energy → 3 bytes       │  words
     │                                                SherpaTtsEngine   on-device VITS voice
  PacketCodec.encode  ITP-1 packet (~16–108 B)           │  PCM
     │                                                AlertPolicy       distress → max alarm volume
  Transport (send) ──── Wi-Fi / BLE / RFCOMM / ────▶  AudioPlayer
                        Wi-Fi Direct / sound
```

### ITP-1, the frozen wire format

`codec/` defines a byte layout that devices will speak to each other for years, so it was fixed and verified on real devices before anything was built on top of it. New capability goes into a new protocol version, never into a redefined byte, and `PacketCodecTest` exists to make an accidental change impossible to do quietly.

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 1 | `MAGIC` | `0xA7`, lets a receiver resynchronise on a raw byte stream |
| 1 | 1 | `VER_FLAGS` | version nibble + flags (speaker sidecar, FEC, final, text mode) |
| 2 | 1 | `SESSION_ID` | identifies the talker |
| 3 | 1 | `SEQ` | with `SESSION_ID`, the key for duplicate suppression |
| 4 | 1 | `LANG_ID` | frozen 0–9 language table |
| 5 | 1 | `INTENT` | routine / alert / distress |
| 6–8 | 3 | `PITCH`, `RATE`, `ENERGY` | quantised prosody |
| 9 | 1 | `PAYLOAD_LEN` | |
| 10 | 1 | `HDR_CRC` | CRC-8 over the header, so a bad packet is rejected before its payload is read |
| 11 | N | `SYMBOLS` | one symbol per byte, or a phrase reference |
| 11+N | 2 | `CRC16` | CRC-16/CCITT-FALSE over everything before it |

The format is deliberately byte-aligned rather than bit-packed: bit-packing would save about 15 bytes and cost days of debugging, and the packet is already ~60 times smaller than audio. The full specification, including the frozen symbol and language tables and reference vectors, is in [TRD §4](docs/TRD.md).

### Phrase codebook: the same sentence in 16 bytes

Disaster traffic is highly repetitive. If both phones already hold a sentence in a numbered list, the message is a 12-bit index and the header becomes the expensive part.

| *"Flood water is rising near the school, send boats"* | Size | Airtime at 300 bps |
|---|---|---|
| Opus voice at 12 kbps | 4,500 B | 2 min 00 s |
| Spelled out as an ITP-1 packet | 62 B | 1.7 s |
| **As a phrase reference** | **16 B** | **0.43 s** |

That is an 11-byte header, a 3-byte reference and a 2-byte CRC. `PhraseCodebookTest` measures the 16 bytes through the real codec rather than asserting it.

It fits the frozen format without a new version: the symbol table reserved slots 6–15 for control from version 1, and `PHRASE_REF` takes slot 6. An older receiver sees an unassigned control symbol and drops the phrase rather than mis-speaking it.

A phrase id is an index, not words, so two phones with different lists would exchange confident nonsense. The lists are therefore **append-only and fingerprinted** (`PhraseCodebook.fingerprint`), and a reference a phone cannot resolve shows as `[phrase 900 — not in this device's codebook]` on screen and stays silent on the speaker. Free speech that is not in the list is never forced onto a phrase that merely looks close; it travels as spoken, and each message's encoding is logged separately so a codebook hit is never credited for bytes it did not save.

### Translation for free

Because line N of every codebook means the same thing in every language, translation is already done by the time the packet arrives:

```
Tamil phone    "வெள்ளம் உயர்கிறது படகுகளை அனுப்புங்கள்"  → id 33   (3 bytes on the wire)
Telugu phone   id 33 → its own line 33, spoken by its own Telugu voice
```

Nothing is translated at send time or at receive time, and the packet is the same 16 bytes it would be within one language. All ten languages carry the full 144-phrase list, so every pair works in both directions: 90 directions, checked as 12,960 phrase lookups against the lists inside the APK. Only codebook phrases translate; free speech arrives as the speaker's words, and the UI shows which of the two happened. The phrase wordings were written line by line against the English list and still need a native speaker of each language to confirm them.

### Links (transports)

Every link implements one `Transport` interface, so nothing above it knows which radio is in use. Framing sits below the transport (`FrameReader`), so stream links like RFCOMM and TCP needed no new parsing code.

| Link | How it reaches the other phone | Carries |
|---|---|---|
| **Automatic** (default) | `CompositeTransport` runs Wi-Fi broadcast and BLE broadcast together; duplicates are removed by `(sessionId, seq)` | everything Wi-Fi can carry |
| Wi-Fi broadcast | UDP multicast to a `239.x` group with TTL 1, on any local network or hotspot, no internet needed | any message (~1,400-byte datagrams) |
| Bluetooth LE broadcast | the message rides inside a legacy BLE advertisement; no pairing, no connection | ~24 usable bytes, i.e. a phrase reference |
| Bluetooth (paired) | RFCOMM socket between two paired phones | any message |
| Wi-Fi Direct | the OS negotiates a group owner, which runs a TCP server | any message |
| Sound | 16-FSK tones in the 1.5–2.4 kHz voice band, RS(15, 9) error correction | up to 120 bytes; a phrase takes ~2.7 s |

Two wrappers sit on top of any link:

- **`FloodRelay`** turns every phone into a repeater, using a seen-set keyed on `(sessionId, seq)`, a hop limit, and random jitter before rebroadcasting so neighbours do not all transmit at the same instant. It is flooding, not routing: no topology, no acknowledgements, which is the right shape for a message whose destination is "anyone at all".
- **`ThrottleWrapper`** limits any link to a chosen bit rate (300 bps, 1 kbps or 2.4 kbps presets), so the narrow-link claim can be run live in the app rather than asserted.

The sound link works because sound is the one channel an app controls down to the waveform: it needs no pairing, no radio permission, works in airplane mode, and its tones sit inside the 300–3400 Hz band every voice radio carries. Each tone is an exact bin of the 512-sample analysis window, so the sixteen are orthogonal; the first 8 ms of each 40 ms slot is ignored to reject room echo; and the RS(15, 9) code corrects up to three wrong tones in every fifteen.

### Languages are data, not code

A language pack declares which recogniser family it needs, its model paths and its own self-test sentence, and the app picks the engine. Nothing in the Kotlin source names a language. Tamil was first added to a running install by copying a folder, with no rebuild and no reinstall:

```bash
adb push build/packs/ta /sdcard/Android/data/com.itantra/files/languages/
```

On the next launch the app logs `packs: en(0), ta(6)` and Tamil appears in the picker.

| | Recognition | Voice |
|---|---|---|
| English | streaming Zipformer 20M (int8), bundled | Piper VITS (int8), bundled |
| Hindi, Telugu, Malayalam, Gujarati | AI4Bharat IndicConformer CTC (int8) | Piper / Mimic3 VITS |
| Tamil, Marathi, Kannada, Odia, Bengali | AI4Bharat IndicConformer CTC (int8) | Meta MMS VITS, converted and quantised in this repo |

The IndicConformer models share one 66 KB vocabulary across all the Indic languages, so each additional language costs only its own model file. Downloads are written to a `<code>.partial` staging folder and renamed only when every file has arrived, and they resume after an interrupted connection, so a half-downloaded pack can never look installed.

### Engineering notes worth keeping

**Measure the model instead of assuming.** Tamil first ran on multilingual Whisper. On the same synthesised test sentence, through the same wrapper:

| Recogniser | Heard | Word error rate |
|---|---|---|
| Whisper base, multilingual | `விளம் வியருகிறது ப` | ~100% |
| **AI4Bharat IndicConformer** | `வெள்ளம் உயர்கிறது படகுகளை அனுப்புங்கள்` | **0%** |

An earlier version of this README claimed no converted Tamil IndicConformer existed. That was wrong, it was stated with more confidence than the search behind it justified, and a whole language ran on the wrong model because of it.

**Reproduce native crashes on the desktop first.** The converted Tamil voice killed the app on load with a native abort and no Java exception. Four build-push-test rounds went into changing the *model*. Running the same files through desktop sherpa-onnx printed the real cause in one second:

```
offline-tts-character-frontend.cc:ReadTokens:66
Error when reading tokens at Line <unk> 58. size: 5
```

The character frontend requires every symbol to be one character, and `<unk>` is five. Deleting that one line made Tamil speak.

**A bad pack can no longer take the app with it.** `runCatching` cannot catch an abort below the JNI boundary, so `TokensFile` now reads and validates the vocabulary in Kotlin before any native code runs, and rejects a malformed one with a message naming the file and line. This closes one specific, observed hole; it is not a general guarantee, because a corrupt `.onnx` graph can still abort the process. `TokensFileTest` pins the exact line that caused the original crash.

**Voices tuned for cheap phones.** The v1 voice builds cut CPU per second of speech from 15.7 s to 0.55 s for Tamil and from 0.46 s to 0.09 s for English, Hindi and Malayalam (measured on one CPU thread). A resampling bug that played 22,050 Hz voices at 16,000 Hz (73% speed, 5.5 semitones low) was fixed in v0.4.0.

### Privacy and network use

- In the field the app contacts no server of any kind: there is no account, no hostname lookup, and recognition, synthesis and every link are local.
- The one network use is **downloading a language pack at setup**, while you still have connectivity, in the same way you would download offline maps before a trip.
- The app declares the `INTERNET` permission only because Android requires it for *any* socket, including the purely local multicast used by Wi-Fi broadcast. Those datagrams go to an administratively scoped `239.x` group with a TTL of 1, so routers do not forward them off the local network. The BLE and sound links need no such permission.

### The narrow-link claim, as arithmetic

`LinkBudget` computes airtime for a real measured packet against published codec bit rates, and the tests pin the numbers. For one 3-second utterance over a 300 bps bearer (LoRa SF12 or satellite short-burst):

| Representation | Size | Airtime |
|---|---|---|
| **iTantra packet** | **75 B** | **2.0 s**, keeps up |
| Opus voice at 12 kbps | 4,500 B | 2 min 00 s |
| Raw 16 kHz PCM | 96,000 B | 42 min 40 s |

### Measured on real phones

**Bluetooth between two handsets** (vivo Y400 Pro 5G → Motorola Edge 60 Pro, RFCOMM, 12 Sep 2026):

```
moto : Received (62 bytes) "Flood water is rising near the school, send boats"
       English · ROUTINE · seq 0      Sent 1 · Received 1 · Dropped 0
```

Throttled to 300 bps on that same live link, the app reported 1.7 s of airtime for the 62-byte packet, against 1 min 01 s for Opus voice and 21 min 54 s for raw audio.

**On a Motorola Edge 60 Pro:**

| | |
|---|---|
| Language pack install (first run) | 190 ms |
| Speech models loaded and ready | 2.8 s |
| Audio frames dropped during capture | 0 |
| Distress alert | alarm volume 3/7 → 7/7, restored to 3/7 afterwards; routine messages leave volume alone |

APKs are split per CPU architecture, so a phone installs only the native libraries it can run.

## Tech stack

| Area | Choice |
|---|---|
| App | Kotlin 2.0, Jetpack Compose (Material 3), Kotlin coroutines and `Flow` |
| On-device speech | sherpa-onnx 1.13.6 (ONNX Runtime): streaming Zipformer, NeMo CTC (IndicConformer), Whisper, VITS (Piper, Mimic3, MMS) |
| Radios | Bluetooth RFCOMM, Bluetooth LE advertising, Wi-Fi Direct, UDP multicast |
| Signal processing | 16-FSK acoustic modem, Reed–Solomon RS(15, 9) over GF(16), CRC-8 / CRC-16 |
| Build | Gradle Kotlin DSL, AGP 8.7, version catalog, per-ABI APK splits; `minSdk` 24, `targetSdk` 34 |
| Tests | JUnit 4 on the JVM (no device needed) |
| Tooling | Python scripts to export, quantise and package the voices |

## Getting started

### Install

Download the latest release from **[Releases](https://github.com/Niranjan266/iTantra/releases/latest)**. Take the `arm64-v8a` APK (smaller, fits nearly every phone made since 2017) or the `universal` one if that refuses to install. It works immediately: no account, no setup, no network. Android will warn about installing from outside the Play Store because these are debug-signed builds. Install it on **both** phones.

To add a language: Settings → *Add a language*. Each download includes its voice.

Pre-release builds are marked as such on the Releases page until the two-phone test has been run on them.

### Build from source

Requires the Android SDK and **JDK 21**. The JDK path is pinned in `gradle.properties` because a system JDK newer than 21 is not supported by the Android build tools.

APKs and models are **release assets, not files in this repository**: the Tamil recogniser alone is 124.6 MB, past GitHub's 100 MB per-file limit. Nothing binary is tracked here, which keeps a clone to a few hundred kilobytes.

```bash
git clone https://github.com/Niranjan266/iTantra.git
cd iTantra

# first time only: fetch the speech runtime and English models (~190 MB, once)
powershell -ExecutionPolicy Bypass -File tools/fetch-models.ps1

# run the unit tests and build the debug APKs
./gradlew testDebugUnitTest assembleDebug

# install on a connected phone
adb install -r app/build/outputs/apk/debug/app-debug.apk

# run only the wire-format tests
./gradlew testDebugUnitTest --tests "com.itantra.codec.*"
```

### Tests

289 JVM unit tests across 22 suites (the v0.4.2 release passes all of them and Android lint).

| Suite | Tests | Covers |
|---|---|---|
| `PhraseCodebookTest` | 28 | phrase compression, and that speech and screen agree |
| `FloodRelayTest` | 24 | mesh relay, hop limit, duplicate suppression, repetition |
| `PacketCodecTest` | 23 | the frozen ITP-1 byte layout |
| `LanguagePackTest` | 20 | reading a pack of any recogniser family, refusing a malformed one |
| `ProsodyExtractorTest`, `WordErrorRateTest`, `VadGateTest` | 17 each | prosody in three bytes, WER with error attribution, speech endpointing |
| `FrameReaderTest` | 15 | reassembling frames from a byte stream |
| `TokensFileTest`, `ThrottleTest`, `AcousticModemTest` | 14 each | vocabulary validation, simulated bit rate, the sound modem |
| `MetricsTest` | 13 | latency percentiles and CSV export |
| `TranslatorTest`, `HindiToTamilTest`, `EveryLanguagePairTest` | 12, 9, 8 | cross-language phrase delivery |
| `ToneTest`, `AlertRulesTest`, `CompositeTransportTest`, `ResamplerTest` | 10, 9, 7, 6 | audio rendering, alert overrides, multi-link, resampling |
| `ReedSolomon16Test`, `CrcTest`, `OverTheAirTest` | 5, 5, 2 | error correction, checksums against published check values, modem end to end |

## Project structure

```
app/src/main/java/com/itantra/
  codec/        ITP-1 wire format (the frozen contract), phrase codebook, translator
  transport/    Transport interface, Wi-Fi / BLE / RFCOMM / Wi-Fi Direct / sound links,
                FloodRelay mesh, ThrottleWrapper, LinkBudget
  acoustic/     16-FSK modem and Reed–Solomon RS(15, 9)
  asr/          recogniser engines (Zipformer, NeMo CTC, Whisper)
  tts/          sherpa-onnx VITS voices
  vad/          speech detection and endpointing
  prosody/      pitch, rate and energy → 3 bytes
  lang/         language packs, catalogue, downloader, tokens validation
  alert/        volume override for distress messages
  session/      the state machine that ties capture, encode, send, receive and speak together
  telemetry/    latency tracking, word error rate, CSV export
  ui/           Compose screens: talk, messages, devices, settings, diagnostics
app/src/main/assets/
  codebooks/    the ten parallel 144-phrase lists
  languages/    bundled English pack and the downloadable-language catalogue
app/src/test/   JVM unit tests
docs/           PRD, TRD, implementation plan
packs/ta/       the Tamil pack definition
tools/          model fetch, voice export / quantise / package scripts
```

## Status

| Phase | State |
|---|---|
| 0 — buildable skeleton | done |
| 1 — the frozen wire format | done, verified on device |
| 2 — Bluetooth between two phones | done, 62 B delivered vivo → Moto over RFCOMM |
| 3 — microphone and playback | done, capture and recognition verified by on-device self-test |
| 4 — alerting | done, volume override and restore verified on device |
| 5 — real speech models | done, English verified end to end, all ten languages speak |
| 6 — languages as data | done, Tamil added to a running install with no rebuild |
| 7 — narrow-link proof | built and unit-tested |
| 8 — measurement | done, WER, prosody, percentiles and CSV export |

Still open:

- **Two-phone test of the v0.4.x pre-releases** (automatic link, sound link, cross-language phrases).
- **Native-speaker review** of each language's phrase list before it is treated as final; a wrong phrase in an emergency list is worse than a missing one.
- **Hear-your-own-voice check** (Gate 3): capture is proven with 0 dropped frames, but whether the voice *sounds right* needs a person, not adb.
- **True mouth-to-ear latency** needs a shared clock across two handsets; the figure shown today is labelled "Round trip" because that is what it is.
- **Prosody `rate`** saturates, because it is fed a character count where a phoneme count is meant.

### Model licences

The recognition models and the sherpa-onnx runtime are Apache-2.0. The Meta MMS voices used for Tamil, Marathi, Kannada, Odia and Bengali are **CC-BY-NC 4.0 (non-commercial only)**; a commercial deployment would need different voices for those languages. Piper and Mimic3 voices follow their upstream model cards.

## Author

Built by Niranjan D — [niranjand.in](https://www.niranjand.in) · [GitHub](https://github.com/Niranjan266) · [LinkedIn](https://www.linkedin.com/in/niranjan266)
