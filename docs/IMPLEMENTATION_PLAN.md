# iTantra — Implementation Plan

| Field | Value |
|---|---|
| Document version | 1.0 |
| Companion documents | `PRD.md`, `TRD.md` |
| Build machine | Windows 11, Android SDK at `%LOCALAPPDATA%\Android\Sdk`, JDK 21 (Android Studio JBR) |
| Status | Ready to execute |

---

## 0. How this plan works

Eight phases. **Each phase ends with a gate — a specific thing that must be observed working on a
real phone before the next phase starts.** No phase begins on faith in the previous one.

The ordering is chosen so that the two hardest problems are de-risked earliest:

1. **The byte format is built before anything that produces bytes.** It is the frozen contract; if it
   is wrong, everything above it is rewritten. So it is written first, with unit tests, while there
   is nothing else to distract from getting it right.
2. **The Indic model conversion runs as a parallel track starting on day one**, not when it is
   needed in Phase 6. It is the single most likely thing to fail, and finding that out in week 3
   would end the project.

A rule that makes the whole plan work: **`LoopbackTransport` exists from Phase 1.** It carries a
packet from the transmit path straight back into the receive path on the same device. That means the
entire speech pipeline can be built and tested on **one phone**, with no Bluetooth pairing, no second
device, and no radio flakiness in the way. Bluetooth becomes a swap of one object, not a dependency
for daily work.

---

## Phase 0 — Skeleton that builds and installs

**Goal:** a real APK on a real phone. Nothing else.

| File | Purpose |
|---|---|
| `settings.gradle.kts` | project + module declaration, repositories |
| `build.gradle.kts` | root build file, plugin versions |
| `gradle/libs.versions.toml` | version catalog — every dependency version in one place |
| `gradle.properties` | JVM args, AndroidX flags, **`org.gradle.java.home` pinned to JDK 21** |
| `local.properties` | `sdk.dir` — git-ignored, machine-specific |
| `gradle/wrapper/*` | Gradle wrapper so the build is reproducible |
| `app/build.gradle.kts` | Android config, minSdk 24, compileSdk 34, ABI filters |
| `app/src/main/AndroidManifest.xml` | permissions per TRD §3.1, no `INTERNET` |
| `app/src/main/java/com/itantra/MainActivity.kt` | Compose entry point |
| `app/src/main/java/com/itantra/ui/PttScreen.kt` | the button, placeholder behaviour |
| `.gitignore` | build outputs, `local.properties`, model files |

**Gate 0:** `gradlew assembleDebug` succeeds, `adb install` puts it on the phone, the app opens and
shows a PTT button. Nothing works when pressed. That is correct for this phase.

**Expected friction:** the first build downloads the Gradle distribution and the Android build
dependencies — several hundred MB, 5–15 minutes. Every subsequent build is ~30 seconds. This is
normal and is not a failure.

---

## Phase 1 — The frozen contract

**Goal:** the packet format exists, is tested, and can travel through a transport — with no audio and
no AI anywhere in the system.

| Component | What it does |
|---|---|
| `codec/Symbols.kt` | The full frozen symbol table from TRD §4.4 |
| `codec/LanguageId.kt` | The frozen 0–9 language table from TRD §4.3 |
| `codec/Crc.kt` | CRC-8 and CRC-16/CCITT-FALSE |
| `codec/Packet.kt` | The data class — every field of TRD §4.2 |
| `codec/PacketCodec.kt` | `encode(Packet): ByteArray` and `decode(ByteArray): Result<Packet>` |
| `transport/Transport.kt` | The interface: `send(bytes)`, `incoming: Flow<ByteArray>`, `connect()`, `close()` |
| `transport/LoopbackTransport.kt` | Sends straight back to itself |
| `session/SessionController.kt` | The state machine skeleton |
| `telemetry/LatencyTracker.kt` | Stage timestamps |
| `ui/MetricsHud.kt` | Displays bytes sent, latency, error count |

Unit tests written in the same phase, per TRD §9.1 — including the reference vectors, so the format
is pinned by tests rather than by intention.

**Gate 1:** Press PTT on one phone. A hardcoded sentence is encoded, sent through
`LoopbackTransport`, decoded, and displayed on screen along with **the exact byte count** and a hex
dump. All unit tests green.

This gate is more important than it looks: at this point the byte-size claim — the central
differentiator of the whole project — is already measurable and demonstrable.

---

## Phase 2 — Two phones

**Goal:** the same bytes cross a real air gap.

| Component | What it does |
|---|---|
| `transport/BluetoothRfcommTransport.kt` | RFCOMM server + client on a fixed UUID |
| `ui/` connection state | pair, connect, show status |
| Runtime permission flow | `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` on API 31+, legacy below |

Nothing above the transport changes. That is the point of the interface — this phase touches one
file and one screen.

**Gate 2:** Two phones, both in **airplane mode with Bluetooth on**. Press PTT on A; the hex dump and
byte count appear on B. Fifty consecutive sends with zero loss.

---

## Phase 3 — Real audio

**Goal:** the microphone and the speaker work, and audio timing is understood before any model is
introduced.

| Component | What it does |
|---|---|
| `audio/AudioCapture.kt` | `AudioRecord`, 16 kHz mono, 20 ms frames, emitted as a `Flow` |
| `audio/AudioPlayer.kt` | `AudioTrack` in streaming mode |
| `vad/VadGate.kt` | Energy-threshold speech detection + endpointing state machine (Silero replaces the detector in Phase 4; the state machine stays) |
| Debug path | Record while PTT held → play back locally on release |

**Gate 3:** Hold PTT, speak, release — your own voice plays back from the same phone. The metrics
overlay shows frames captured, frames dropped (must be zero), and the endpoint delay in
milliseconds.

> Audio is deliberately **not** sent over the transport in this phase. Raw audio would be ~96 KB for
> 3 seconds and would take 13 minutes over a 1 kbps link. Proving that pointless is not worth the
> code; the local playback proves the same plumbing.

---

## Phase 4 — Smart listening and the distress path

**Goal:** the two requirements that are pure engineering, finished before the ML work starts.

| Component | What it does |
|---|---|
| `vad/SileroVad.kt` | ONNX VAD, ~1.8 MB, on a background thread |
| `vad/VadGate.kt` | Now driven by Silero instead of energy |
| `alert/AlertPolicy.kt` | DISTRESS: raise `STREAM_ALARM` to max, take audio focus with `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`, ignore incoming interrupts until playback completes, then restore the user's volume |
| `ui/` intent selector | ROUTINE / ALERT / DISTRESS control |

**Gate 4:** (a) Idle CPU measured with Android Profiler while the VAD runs continuously — recorded in
`docs/measurements/`, target ≤ 5%. (b) Send a DISTRESS packet to a phone that is on silent with
volume at zero; it plays at full volume and cannot be stopped mid-message.

Both PRD acceptance criteria §10.4 and the idle-CPU half of the efficiency metric are now satisfied,
with numbers, before a single speech model exists.

---

## Phase 5 — The real loop, in English

**Goal:** speech in, speech out. The system becomes what it claims to be.

| Component | What it does |
|---|---|
| sherpa-onnx AAR | Added to `libs.versions.toml` and `app/build.gradle.kts` |
| `asr/AsrEngine.kt` | Interface: `accept(frames)`, `partial: Flow<String>`, `finalResult()` |
| `asr/SherpaAsrEngine.kt` | Streaming Zipformer, English, int8 |
| `tts/TtsEngine.kt` | Interface: `synthesize(symbols): Flow<PcmChunk>` |
| `tts/SherpaTtsEngine.kt` | VITS, chunked by clause so playback starts early |
| `codec/PhonemeCodec.kt` | Text ⇄ symbols. English begins in `TEXT_MODE`; the phoneme path is switched on in Phase 6 |
| `session/SessionController.kt` | Now wires the full path |

Partial-prefix commit (PRD F-05) is implemented here: stable ASR prefixes are emitted every ~300 ms
with the `FINAL` flag clear, so synthesis at the far end begins before the sentence ends.

**Gate 5:** Two phones in airplane mode. Speak an English sentence into A. Hear it spoken from B.
Measured mouth-to-ear latency displayed on screen, target under 1 second. **This is Milestone M3 and
it is the moment the project becomes real.**

---

## Phase 6 — Languages as data

**Goal:** prove the scaling claim. This is what separates the submission from the field.

| Component | What it does |
|---|---|
| `lang/LanguagePack.kt` | One language's models, phoneme table, script table |
| `lang/LanguagePackManager.kt` | Scans the pack directory, parses `manifest.json`, hot-reloads |
| `ui/LanguagePicker.kt` | Populated entirely from the manifest |
| `codec/PhonemeCodec.kt` | Phoneme mode switched on: CLS symbols for Indic, ARPAbet for English |
| Pack build script | Packages a model directory into a valid pack |
| First Indic pack | Hindi or Tamil, from the parallel model track |
| `transport/WifiDirectTransport.kt` | `WifiP2pManager`; a drop-in `Transport` implementation |

The hardcoding audit happens at the end of this phase: `grep` the source tree for every language
name, ISO code and model filename. Any hit outside `LanguagePackManager` is a bug.

**Gate 6:** (a) The full loop works in an Indic language. (b) A teammate who has never opened the
code adds a third language by copying a folder and editing `manifest.json`, in under 60 seconds,
with no rebuild — PRD §10.5. (c) `grep -ri "tamil\|hindi\|\.onnx" app/src/main/java` returns nothing
outside the pack manager.

---

## Phase 7 — The differentiators

**Goal:** the things nobody else will have.

| Component | What it does | PRD |
|---|---|---|
| `prosody/ProsodyExtractor.kt` | Pitch, rate, energy → 3 bytes; applied to synthesis at the far end | F-08, F-24 |
| `speaker/SpeakerEmbedder.kt` | 32-byte voice identity, sent once per session | F-07, F-23 |
| `prosody/IntentClassifier.kt` | Automatic urgency detection from the voice | F-34 |
| `codec/Fec.kt` | Repeat-send with `SEQ` dedupe; Reed–Solomon parity | F-16 |
| `transport/ThrottleWrapper.kt` | Limits any transport to N bits/sec — the demo weapon | F-52 |
| `telemetry/MetricsStore.kt` | CSV export of every transmission | F-51 |

**Gate 7:** The narrow-link demonstration runs end to end. Throttle set to 1 kbps; iTantra delivers
the sentence in under a second of transmit time while a conventional voice recording of the same
sentence stalls. Recorded on video as demo insurance.

---

## Phase 8 — Proof and hardening

**Goal:** turn claims into measurements and make the demo unbreakable.

- WER harness: labelled held-out set scored on-device, per language, results to `docs/measurements/`
- TTS mean-opinion-score session with native listeners, 20 fixed sentences
- Latency distribution over ≥ 50 runs — report p50 and p95, never a best-case single run
- App size, model sizes, peak RAM, idle CPU — all measured and put on one slide
- Demo rehearsal ×20, including deliberate failure recovery
- Backup: pre-paired spare phone pair, and a recorded video of every acceptance criterion

**Gate 8:** All six PRD §10 acceptance criteria demonstrated on demand, twice in a row, on the actual
demo hardware.

---

## Parallel track — model preparation (starts now, not in Phase 6)

This runs on a laptop in Python, independently of the Android work, from day one.

| Step | Output | Risk |
|---|---|---|
| Obtain pre-converted sherpa-onnx English streaming Zipformer + a VITS voice | Working Phase 5 models | Low — these ship ready to use |
| Obtain Silero VAD ONNX | Phase 4 | Low |
| Build the CLS ↔ ITP-1 symbol mapping table | `phonemes.tsv` per language | Low, but tedious; pure data work |
| Convert an AI4Bharat Indic ASR model (NeMo → ONNX) | Phase 6 Indic pack | **High — this is the project's biggest technical risk** |
| Fallback: locate an already-converted community Indic ONNX model | Phase 6 insurance | Medium |
| Quantise everything to int8 and measure size + accuracy delta | Efficiency metric | Medium |

> **The one instruction that matters most in this document:** attempt the NeMo → ONNX conversion in
> week 1, while there is still time to find another route. If it is first attempted in week 3 and it
> fails, there is no Indic language and no project.

---

## Cut list, in order

If time runs short, remove in exactly this order:

1. Wi-Fi Direct (Bluetooth already satisfies the requirement)
2. Cross-lingual rendering
3. Automatic intent classification (keep the manual selector)
4. Speaker identity sidecar
5. Reed–Solomon FEC (keep simple repeat-send)
6. Languages beyond the second

**Never cut, under any circumstances:** the ITP-1 byte format, the throttled-link demonstration, the
distress path, and real measured numbers. Those four are the submission.

---

## Immediate next actions

| # | Action | Owner |
|---|---|---|
| 1 | Phase 0 — generate the project skeleton and get it onto a phone | now |
| 2 | Connect a phone via USB, enable USB debugging, confirm `adb devices` | you |
| 3 | Start the parallel model track: download the English sherpa-onnx models and attempt one NeMo→ONNX conversion | ML teammate |
| 4 | Phase 1 — the codec and its tests | after Gate 0 |
