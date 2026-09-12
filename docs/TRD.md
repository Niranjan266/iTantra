# iTantra — Technical Requirements Document (TRD)

| Field | Value |
|---|---|
| Document version | 1.0 |
| Companion document | `PRD.md` |
| Target platform | Android 7.0 (API 24) → Android 14 (API 34) |
| Language | Kotlin |
| Status | Approved for build |

---

## 1. Architecture

### 1.1 The signal path

```
                    TRANSMIT SIDE                                RECEIVE SIDE
  ┌────────────────────────────────────────┐      ┌────────────────────────────────────────┐
  │  Microphone                            │      │              Transport (RX)            │
  │      ↓  16 kHz PCM, 20 ms frames       │      │                    ↓ bytes             │
  │  AudioCapture                          │      │              PacketCodec.decode()      │
  │      ↓                                 │      │                    ↓ Packet            │
  │  VadGate  ── silence ──▶ discard       │      │              Validate CRC / dedupe     │
  │      ↓ speech                          │      │                    ↓                   │
  │  AsrEngine (streaming)                 │      │              PhonemeCodec.decode()     │
  │      ↓ symbol stream + partials        │      │                    ↓ symbols           │
  │  ProsodyExtractor ─┐                   │      │              TtsEngine (chunked)       │
  │  SpeakerEmbedder ──┤                   │      │                    ↓ PCM chunks        │
  │      ↓             ↓                   │      │              AlertPolicy (volume)      │
  │  PhonemeCodec.encode()                 │      │                    ↓                   │
  │      ↓ symbols                         │      │              AudioPlayer               │
  │  PacketCodec.encode()                  │      │                    ↓                   │
  │      ↓ ≤96 bytes                       │      │              Speaker                   │
  │  Transport (TX) ───────────────────────┼──────┼──▶                                     │
  └────────────────────────────────────────┘      └────────────────────────────────────────┘
                                    ▲                          ▲
                                    └──── LatencyTracker ──────┘
                                        (timestamps both ends)
```

**The controlling idea:** the only thing that crosses the link is a `Packet`. Everything to the left
of `Transport` and everything to the right of it are independent programs that share nothing but the
byte format defined in §4. That format is a frozen contract — it is versioned, and once shipped its
meaning never changes.

### 1.2 Module map

```
app/src/main/java/com/itantra/
├── MainActivity.kt              app entry, permissions, screen wiring
├── ui/
│   ├── PttScreen.kt             the walkie-talkie screen (Compose)
│   ├── MetricsHud.kt            live latency / bytes / RTF overlay
│   └── LanguagePicker.kt        populated from the manifest, never hardcoded
├── session/
│   ├── SessionController.kt     the state machine; the only class that knows the whole flow
│   └── SessionState.kt          IDLE | LISTENING | ENCODING | SENDING | RECEIVING | SPEAKING
├── audio/
│   ├── AudioCapture.kt          AudioRecord wrapper, 16 kHz mono, 20 ms frames
│   └── AudioPlayer.kt           AudioTrack wrapper, streaming write
├── vad/
│   ├── VadGate.kt               speech/silence + endpointing state machine
│   └── SileroVad.kt             ONNX inference (Phase 4; energy VAD before that)
├── asr/
│   ├── AsrEngine.kt             interface
│   ├── SherpaAsrEngine.kt       sherpa-onnx streaming implementation
│   └── FakeAsrEngine.kt         returns a canned sentence — used before models land
├── tts/
│   ├── TtsEngine.kt             interface
│   ├── SherpaTtsEngine.kt       sherpa-onnx VITS implementation
│   └── AndroidTtsEngine.kt      system TTS — bootstrap only, NOT for submission
├── codec/
│   ├── Symbols.kt               the frozen symbol table (§4.4)
│   ├── PhonemeCodec.kt          text/phoneme string ⇄ symbol bytes
│   ├── Packet.kt                the data class
│   ├── PacketCodec.kt           Packet ⇄ ByteArray
│   ├── Crc.kt                   CRC-8 and CRC-16/CCITT
│   └── Fec.kt                   repeat-send and Reed–Solomon (Phase 7)
├── prosody/
│   ├── ProsodyExtractor.kt      pitch / rate / energy → 3 bytes
│   └── IntentClassifier.kt      ROUTINE | ALERT | DISTRESS (Phase 7)
├── speaker/
│   └── SpeakerEmbedder.kt       32-byte voice identity (Phase 7)
├── transport/
│   ├── Transport.kt             interface — the seam of the whole system
│   ├── LoopbackTransport.kt     same-device echo; lets us test with ONE phone
│   ├── BluetoothRfcommTransport.kt
│   ├── WifiDirectTransport.kt
│   └── ThrottleWrapper.kt       decorator that limits any transport to N bits/sec
├── lang/
│   ├── LanguageId.kt            the frozen 0–9 table (§4.3)
│   ├── LanguagePack.kt          one language's models + tables
│   └── LanguagePackManager.kt   discovers packs, parses manifest.json
├── alert/
│   └── AlertPolicy.kt           volume override, non-interruptible playback
└── telemetry/
    ├── LatencyTracker.kt        stage timestamps
    └── MetricsStore.kt          in-memory ring buffer + CSV export
```

## 2. Technology decisions and rationale

| Decision | Choice | Why this and not the alternative |
|---|---|---|
| App language | Kotlin | sherpa-onnx's Kotlin/JNI path is its best-supported Android binding; no bridge layer to write |
| UI | Jetpack Compose | One-file screens; a large PTT button and a metrics overlay need no XML |
| Inference runtime | ONNX Runtime, via sherpa-onnx | Apache-2.0, ships a prebuilt Android AAR containing streaming ASR, VITS TTS and VAD. Satisfies constraint C-03 |
| Speech recognition | Streaming Zipformer (transducer) | True streaming — emits partial results mid-utterance, which is where the latency score is won |
| Speech synthesis | VITS, phoneme input | Single model can serve multiple languages when the input is already language-neutral |
| Voice activity | Silero VAD ONNX (~1.8 MB) | Runs on a low-priority thread at well under 1% CPU; this is the evidence for the idle-CPU metric |
| Transport (P0) | Bluetooth RFCOMM | Simplest reliable stream socket between two Android devices; pairs once, then just works |
| Transport (P1) | Wi-Fi Direct (`WifiP2pManager`) | Higher throughput and range; same `Transport` interface, so it is a drop-in |
| Concurrency | Kotlin coroutines + `Flow` | Audio frames are naturally a stream; back-pressure is built in |
| Build | Gradle Kotlin DSL, AGP 8.x | Version catalog keeps dependency versions in one file |

### 2.1 Explicitly rejected

| Rejected | Reason |
|---|---|
| SMS as the transport | Requires a functioning cellular network — the exact thing the scenario assumes has failed. Not mentioned anywhere in the problem statement. Kept only as an optional Phase-8 fallback bearer, never as the primary path |
| React Native / Capacitor / Cordova | No sherpa-onnx binding; models would run in a JS/browser layer and fail the efficiency metric on a low-end device |
| Cloud speech APIs | Prohibited by constraint C-02 |
| Android's built-in `TextToSpeech` for the final build | Not open-source, vendor-dependent, and unavailable for most Indic languages on cheap phones. Used **only** as a Phase-2 bootstrap so that the plumbing can be tested before real models arrive, then deleted |

## 3. Build configuration

| Setting | Value | Note |
|---|---|---|
| `compileSdk` | 34 | Already installed |
| `minSdk` | 24 | Android 7.0 — covers the low-end field device |
| `targetSdk` | 34 | |
| JDK for Gradle | **21** (`C:\Program Files\Android\Android Studio\jbr`) | Pinned in `gradle.properties`. The system JDK 26 is too new for AGP and must not be used |
| Android SDK path | `C:\Users\niran\AppData\Local\Android\Sdk` | Set in `local.properties`, which is git-ignored |
| ABI filters | `arm64-v8a`, `armeabi-v7a` | Real phones only; drops ~40% of native library size |
| Native debug symbols | stripped in release | Size metric |

### 3.1 Permissions

| Permission | Why | Notes |
|---|---|---|
| `RECORD_AUDIO` | Microphone | Runtime request |
| `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` | RFCOMM on API 31+ | Runtime request |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` | RFCOMM on API ≤ 30 | `maxSdkVersion="30"` |
| `ACCESS_FINE_LOCATION` | Required by Android for Wi-Fi Direct discovery | Phase 6 only; declared with a clear in-app explanation |
| `NEARBY_WIFI_DEVICES` | Wi-Fi Direct on API 33+ | `neverForLocation` flag set |
| `MODIFY_AUDIO_SETTINGS` | Raise volume for DISTRESS | |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` | Keep listening when screen is off | Phase 6 |

**No `INTERNET` permission is declared.** This is deliberate and is the strongest possible evidence
for the "fully offline" requirement: the app is *incapable* of network access. Language packs are
side-loaded or installed by a separate provisioning step.

## 4. The wire format — `ITP-1`

This is the core of the product and the part that must be designed correctly on the first attempt,
because it is a contract that cannot be changed later without breaking every deployed device.

### 4.1 Design rules

1. **Fixed-size header.** A receiver must be able to decide whether a packet is valid before it has
   read the payload.
2. **Self-describing.** Version, language and length travel with the data. No out-of-band state.
3. **Byte-aligned.** Bit-packing would save ~15 bytes and cost days of debugging. We are already
   60× better than audio; the extra squeeze is not worth the risk. Bit-packing is a Phase-8 option.
4. **Every field is fixed forever at v1.** New capabilities go in new versions, not by redefining
   existing bytes.

### 4.2 Packet layout

```
 Offset  Size  Field           Description
 ────────────────────────────────────────────────────────────────────────────
   0      1    MAGIC           0xA7 — frame sync, lets a receiver resynchronise
                               after corruption on a raw serial link
   1      1    VER_FLAGS       high nibble = protocol version (1)
                               low nibble  = flags, see §4.2.1
   2      1    SESSION_ID      random per session; identifies the talker so the
                               receiver knows when the speaker identity changed
   3      1    SEQ             sequence number, wraps 0–255; used for dedupe
                               when FEC repeat-send is on
   4      1    LANG_ID         source language, see §4.3
   5      1    INTENT          0 = ROUTINE, 1 = ALERT, 2 = DISTRESS
   6      1    PITCH           mean F0, quantised (see §4.5)
   7      1    RATE            symbols per second, quantised
   8      1    ENERGY          RMS level, quantised
   9      1    PAYLOAD_LEN     number of symbols that follow, 0–255
  10      1    HDR_CRC         CRC-8 (poly 0x07) over bytes 0–9
 ────────────────────────────────────────────────────────────────────────────
  11     32    SPEAKER_EMB     present ONLY if flag SPKR_PRESENT is set;
                               32-byte quantised voice identity
 ────────────────────────────────────────────────────────────────────────────
   *      N    SYMBOLS         N = PAYLOAD_LEN bytes, one symbol per byte (§4.4)
 ────────────────────────────────────────────────────────────────────────────
   *      2    CRC16           CRC-16/CCITT-FALSE over every preceding byte
 ────────────────────────────────────────────────────────────────────────────

 Total = 13 + N bytes,  or 45 + N when the speaker sidecar is attached.
```

**Worked size example — a 20-word Hindi sentence**

| Representation | Size |
|---|---|
| Raw 16 kHz 16-bit PCM, 5 seconds | 160,000 bytes |
| Opus at 12 kbps | ~7,500 bytes |
| Codec2 at 700 bps | ~440 bytes |
| UTF-8 Devanagari text | ~150 bytes |
| **ITP-1, 62 symbols** | **75 bytes** |
| ITP-1 with the speaker sidecar (first packet only) | 107 bytes |

At 1,000 bits per second, a 75-byte packet takes **0.6 seconds** to transmit. The Opus version takes
60 seconds. That single comparison is the demonstration described in PRD §10.3.

#### 4.2.1 Flag nibble (byte 1, low nibble)

| Bit | Name | Meaning |
|---|---|---|
| 0 | `SPKR_PRESENT` | A 32-byte speaker embedding follows the header |
| 1 | `FEC_ON` | Reed–Solomon parity is appended after CRC16 |
| 2 | `FINAL` | This is the last packet of the utterance. If clear, this is a partial prefix and more will follow |
| 3 | `TEXT_MODE` | Payload is UTF-8 text rather than symbol ids. Used for debugging, for English fallback, and for any language whose phoneme pack is not installed |

`TEXT_MODE` is important: it means the system degrades gracefully instead of failing. If the phoneme
path is not ready for a language, the same packet carries plain text and everything downstream still
works.

### 4.3 Language identifiers — frozen

These numbers are permanent. They are never renumbered, never reordered, and never reused.

| ID | Language | Script | ISO code |
|---|---|---|---|
| 0 | English | Latin | `en` |
| 1 | Hindi | Devanagari | `hi` |
| 2 | Gujarati | Gujarati | `gu` |
| 3 | Marathi | Devanagari | `mr` |
| 4 | Kannada | Kannada | `kn` |
| 5 | Malayalam | Malayalam | `ml` |
| 6 | Tamil | Tamil | `ta` |
| 7 | Telugu | Telugu | `te` |
| 8 | Odia | Odia | `or` |
| 9 | Bengali | Bengali | `bn` |
| 254 | (reserved for future languages 10+) | | |
| 255 | UNSPECIFIED / auto-detect | | |

### 4.4 Symbol space — frozen

One byte per symbol, 256 slots. Allocation:

| Range | Count | Purpose |
|---|---|---|
| `0` | 1 | `NUL` — padding, never emitted |
| `1` | 1 | **`WORD_BOUNDARY`** |
| `2` | 1 | `UTTERANCE_END` |
| `3` | 1 | `SENTENCE_BOUNDARY` (full stop / danda) |
| `4` | 1 | `QUESTION` |
| `5` | 1 | `PAUSE_SHORT` (comma) |
| `6–15` | 10 | reserved control codes |
| `16–119` | 104 | **Indic phoneme set** — Common Label Set (CLS), the ~55-symbol inventory defined by the Indian TTS consortium precisely so that one label set spans all Indian languages. Slots are pre-allocated with room to grow |
| `120–179` | 60 | **English phoneme set** — ARPAbet (~44 symbols) |
| `180–199` | 20 | numeral tokens (spoken digits) |
| `200–239` | 40 | reserved for future expansion |
| `240–255` | 16 | vendor / experimental, never used in released builds |

> **Why `WORD_BOUNDARY` exists.** Recognising phonemes rather than words means the model does not
> naturally produce spaces, which would make the received message impossible to display as readable
> text. Reserving an explicit boundary symbol from version 1 solves this permanently at a cost of
> roughly one byte per word. This is the concrete answer to the risk noted in PRD §11.

### 4.5 Prosody quantisation

| Field | Source | Encoding |
|---|---|---|
| `PITCH` | Mean fundamental frequency over the utterance | 50–400 Hz mapped linearly to 0–255; 0 means "not measured" |
| `RATE` | Symbols per second | 0–25 sym/s mapped to 0–255 |
| `ENERGY` | RMS amplitude in dBFS | −60 to 0 dB mapped to 0–255 |

The receiving synthesiser scales its output pitch and speaking rate toward these values. The result
is that a panicked, fast, loud sender *sounds* panicked, fast and loud at the far end — information
that a plain-text pipeline destroys entirely.

### 4.6 Reference vectors (regression tests must pass these)

```
Empty ROUTINE English utterance, session 0x2A, seq 0:
  A7 10 2A 00 00 00 00 00 00 00 <hdrcrc> <crc16lo> <crc16hi>
  → 13 bytes

"hello" as 4 ARPAbet symbols + UTTERANCE_END, DISTRESS, Hindi:
  A7 14 2A 01 01 02 <p> <r> <e> 05 <hdrcrc> 78 79 7B 7F 02 <crc16>
  → 18 bytes
```

## 5. Threading model

| Thread | Priority | Work | Must never block on |
|---|---|---|---|
| Main / UI | default | Compose rendering, button events | anything |
| Audio capture | `THREAD_PRIORITY_URGENT_AUDIO` | `AudioRecord.read()` in a tight loop, push to `Flow` | inference, IO |
| VAD | `THREAD_PRIORITY_BACKGROUND` | Silero inference on 30 ms frames | ASR |
| ASR | `THREAD_PRIORITY_DEFAULT` | Zipformer forward pass | UI, transport |
| TTS | `THREAD_PRIORITY_DEFAULT` | VITS forward pass, chunk by clause | UI |
| Audio playback | `THREAD_PRIORITY_URGENT_AUDIO` | `AudioTrack.write()` | inference |
| Transport IO | `THREAD_PRIORITY_BACKGROUND` | blocking socket read/write | UI |

Rule: **the audio threads never touch a model and the model threads never touch the audio device.**
They communicate only through bounded channels. A dropped frame is acceptable; a blocked audio
thread produces an audible glitch and is not.

## 6. Latency budget

For a 3-second utterance on a 4-core low-end device. These are targets, and each has an owning
module that must report its actual measured value through `LatencyTracker`.

| Stage | Budget | Owner |
|---|---|---|
| Microphone buffer | 30 ms | `AudioCapture` |
| VAD endpoint decision after speech stops | 250 ms | `VadGate` |
| ASR tail compute (RTF ≈ 0.25 on the final chunk) | 150 ms | `SherpaAsrEngine` |
| Encode to packet | 5 ms | `PacketCodec` |
| Transmit 75 bytes over Bluetooth RFCOMM | 30 ms | `Transport` |
| Decode + validate | 5 ms | `PacketCodec` |
| TTS first audio chunk (RTF ≈ 0.4, synthesised clause by clause) | 300 ms | `SherpaTtsEngine` |
| `AudioTrack` buffer to speaker | 60 ms | `AudioPlayer` |
| **Total mouth-to-ear** | **≈ 830 ms** | |

With partial-prefix commit enabled (`FINAL` flag clear), the first audio begins at the far end
*before the speaker has finished the sentence*, so perceived latency is lower still.

**We claim sub-1-second and we do not claim sub-500 ms.** An evaluator who knows the field will
check, and an inflated number costs more than it gains.

## 7. Storage layout — language packs

Packs live in app-private external storage so they can be copied on with `adb push` and, in a real
deployment, provisioned from an SD card.

```
/Android/data/com.itantra/files/languages/
├── manifest.json
├── en/
│   ├── pack.json          metadata: id, name, model filenames, sample rate
│   ├── asr/               *.onnx encoder / decoder / joiner + tokens.txt
│   ├── tts/               *.onnx + tokens.txt + espeak-ng-data/ (if needed)
│   └── phonemes.tsv       phoneme label ⇄ ITP-1 symbol id
└── hi/
    └── (identical structure)
```

`manifest.json`:

```json
{
  "manifest_version": 1,
  "installed": [
    { "id": 0, "code": "en", "name": "English", "dir": "en", "asr_mb": 22, "tts_mb": 28 },
    { "id": 1, "code": "hi", "name": "हिन्दी",  "dir": "hi", "asr_mb": 26, "tts_mb": 30 }
  ]
}
```

**The hardcoding ban.** The string `"Tamil"`, the string `"en"`, and any model filename must never
appear in Kotlin source. `LanguagePackManager` is the single component that reads the filesystem;
everything else receives a `LanguagePack` object. The acceptance test for this is in PRD §10.5:
a teammate adds a language by copying a folder, without opening a `.kt` file.

## 8. Error handling

| Condition | Behaviour |
|---|---|
| CRC mismatch | Packet silently discarded, error counter incremented, shown in the metrics overlay |
| Unknown `LANG_ID` | Packet accepted; rendered with the receiver's default pack; a warning is displayed |
| Unknown symbol id | That symbol is skipped, the rest of the utterance is still spoken |
| `PAYLOAD_LEN` exceeds the received byte count | Packet discarded as truncated |
| Duplicate `SEQ` within 3 seconds | Discarded (this is how FEC repeat-send is de-duplicated) |
| Transport disconnects mid-utterance | State machine returns to `IDLE`, UI shows disconnected, automatic reconnect attempted |
| Language pack missing or corrupt | That language is hidden from the picker; the app still runs |
| Microphone permission denied | PTT button disabled with an explanatory message; receive path still works |

Guiding principle: **a bad packet must never crash the receiver, and a missing language must never
crash the app.** In a distress system, degraded operation always beats termination.

## 9. Test plan

### 9.1 Unit tests (JVM, no device)

- `PacketCodec` round-trips every field across the full value range
- Reference vectors in §4.6 encode byte-for-byte
- CRC-8 and CRC-16 verified against published test vectors
- Every single-bit corruption of a 75-byte packet is detected
- Truncated and over-long payload declarations are rejected
- `PhonemeCodec` round-trips symbol ↔ label for all 256 slots
- `LanguagePackManager` handles: missing manifest, malformed JSON, pack directory absent, duplicate ids

### 9.2 Instrumented tests (on device)

- `LoopbackTransport` carries a packet end to end and the audio plays
- `AudioCapture` delivers frames at the expected rate for 60 seconds without drops
- DISTRESS raises the stream volume and restores the user's setting afterwards
- Adding a pack directory at runtime makes it appear in the picker without restart

### 9.3 System tests (two devices)

- 50 consecutive transmissions with zero loss over Bluetooth
- Latency distribution recorded and exported; p50 and p95 both reported
- Throttled to 1 kbps: delivery still succeeds
- Airplane mode with Bluetooth on: full loop works

### 9.4 Accuracy harness

- WER computed on-device against a held-out labelled set, per language
- TTS mean-opinion-score collected from native listeners on 20 fixed sentences
- Results committed to `docs/measurements/` as CSV — never quoted from memory

## 10. Traceability

Every PRD requirement maps to an owning module.

| PRD | Module | Phase |
|---|---|---|
| F-01, F-03 | `audio/AudioCapture`, `vad/VadGate` | 3, 4 |
| F-02, F-06 | `vad/SileroVad` | 4 |
| F-04, F-05 | `asr/SherpaAsrEngine` | 5 |
| F-10 … F-12 | `codec/PacketCodec`, `codec/Crc` | 2 |
| F-13 … F-15 | `transport/*` | 2, 6 |
| F-16 | `codec/Fec`, `transport/ThrottleWrapper` | 7 |
| F-20, F-21 | `tts/SherpaTtsEngine`, `audio/AudioPlayer` | 5 |
| F-22 | `lang/LanguagePack` script tables | 6 |
| F-23, F-24 | `speaker/SpeakerEmbedder`, `prosody/*` | 7 |
| F-30 … F-33 | `alert/AlertPolicy` | 4 |
| F-40 … F-45 | `lang/LanguagePackManager` | 6 |
| F-50 … F-53 | `telemetry/*`, `ui/MetricsHud` | 2, 7 |

---

*Next document: `IMPLEMENTATION_PLAN.md` — the order in which this gets built and how each step is
proven before the next begins.*
