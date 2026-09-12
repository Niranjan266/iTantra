# iTantra — Product Requirements Document (PRD)

| Field | Value |
|---|---|
| Product | iTantra — Indian Multilingual TTS & STT Aided Neural Transceiver |
| SIH Problem Statement | 26173 |
| Organisation | ISRO / Department of Space |
| Category | Software · Smart Automation |
| Document version | 1.0 |
| Status | Approved for build |

---

## 1. The problem in one paragraph

In a disaster, the cellular network and internet are the first things to fail. What survives is a
**narrow radio link** — a satellite backhaul, an HF/VHF set, a LoRa module, or a degraded tower —
capable of maybe 100 to 2,000 bits per second. Voice cannot fit through that pipe: even heavily
compressed speech needs 6,000–24,000 bits per second. Text fits easily, but text excludes the
people most likely to need help — those who cannot read or write, or who read a different script
than the rescuer. So today the field operator must choose between a channel that fails and a
channel that excludes.

## 2. The product idea in one paragraph

iTantra removes the choice. The sender **speaks**; the receiver **hears a voice**. Neither person
ever sees text. But what actually crosses the radio link is not audio — it is a ~60-byte encoded
representation of what was said. The phone at the sending end converts speech to a compact symbol
stream on-device; the phone at the receiving end converts that stream back into natural speech
on-device. **Language itself becomes the compression codec**, achieving roughly 100× the
compression of any acoustic codec while the human experience at both ends stays pure voice.

## 3. Who uses this

| Persona | Situation | What they need |
|---|---|---|
| **Village reporter** (primary) | Flood/landslide, no network, low literacy, ₹8,000 phone | Press a button, speak in their own language, be understood |
| **Rescue coordinator** (primary) | Control room or field vehicle, different mother tongue | Hear the message clearly, immediately, know how urgent it is |
| **Relay operator** (secondary) | Operates the radio/satellite equipment the phones plug into | The phone must feed bytes to an external radio over a cable |
| **Evaluator / judge** (tertiary) | Demo table at SIH | Must see the loop work and see the numbers |

## 4. Goals

| # | Goal | Why it matters |
|---|---|---|
| G1 | Voice in → voice out across a link too narrow for audio | The entire point of the product |
| G2 | Fully offline, on-device, after installation | ISRO hard requirement; no internet exists in a disaster |
| G3 | Runs on a low/mid-range Android phone | The field device is cheap hardware, not a flagship |
| G4 | Architecture supports all 10 mandated languages | Inclusion is the stated purpose |
| G5 | Mouth-to-ear latency under 1 second | Conversation must feel like a walkie-talkie |
| G6 | Urgency is preserved and acted upon | Distress messages must override everything |
| G7 | Every claim is a measured number, not an adjective | This is how the submission is scored |

## 5. Non-goals (explicitly out of scope)

- Full machine translation between languages (cross-script rendering only)
- Group / multi-party calls (point-to-point only)
- Message history, contacts, chat UI, accounts, cloud sync
- Encryption and authentication (noted as future work; not evaluated)
- Play Store distribution and its policy surface
- iOS
- **Any custom hardware.** No radio modules, no microcontrollers, no external boards.
  See §5.1.

### 5.1 Why this is a software-only project

The problem statement is filed under **Category: Software**, and every scored item —
model size, app size, idle CPU, word error rate, TTS intelligibility, latency, real-time
factor — is a property of the Android application. The single hardware sentence in the
brief is a constraint on the *phone*: "The Android application must run smoothly on Low
and Mid range mobile phones."

ISRO's own acceptance test needs no extra equipment either: *"two phones with same app
one in TTS mode and another in STT mode can be connected via wifi or Bluetooth and it
should work like a walkie talkie."* Two phones. Nothing else.

**How the long-distance claim is proved without hardware.** In a real deployment the
phone hands its bytes to whatever bearer is available — a LoRa module, an HF set, a
satellite terminal — over the same Bluetooth link this app already speaks. Those bearers
are slow: roughly 300 bps to 2 kbps. Rather than buy one, we *simulate* the constraint
in software (F-52) and demonstrate the consequence directly:

| Over a 300 bps bearer | Transit time |
|---|---|
| A 3-second compressed voice note (~4,500 B) | ~2 minutes — fails in practice |
| **One iTantra packet (75 B)** | **~2 seconds** |

This is stronger evidence than a hardware demo, not weaker: it is arithmetic a judge can
check, it is reproducible on any two phones, and it cannot fail on stage because a board
came loose. The bearer is an integration detail; the compression is the invention.

## 6. Product scope — the two operating modes

The problem statement requires both.

### 6.1 PTT mode (Push-To-Talk) — default

Behaves like a walkie-talkie. The user holds a large on-screen button, speaks, releases. The link is
half-duplex: while transmitting, the device does not play incoming audio. This is the mode that
survives on a narrow link.

### 6.2 Phone mode (PTT switched off)

Behaves like a normal call. Continuous listening with automatic endpointing, no button held. Both
directions are open. Higher bandwidth demand, used when the link is good.

## 7. Feature requirements

Priority key — **P0** must exist for the submission to be valid; **P1** is required for a competitive
score; **P2** is a differentiator we ship if time allows.

### 7.1 Capture and recognition

| ID | Requirement | Priority |
|---|---|---|
| F-01 | Capture microphone audio at 16 kHz mono while PTT is held | P0 |
| F-02 | Detect speech vs silence with a lightweight always-on detector | P0 |
| F-03 | Automatically end an utterance after a configurable pause (default 500 ms) | P0 |
| F-04 | Convert speech to a symbol stream fully on-device | P0 |
| F-05 | Emit stable partial results every ~300 ms rather than waiting for the sentence to end | P1 |
| F-06 | Keep CPU below 5% while idle-listening (detector running, recogniser asleep) | P1 |
| F-07 | Estimate speaker voice identity once per session | P2 |
| F-08 | Estimate pitch, rate and energy per utterance | P2 |

### 7.2 Encoding and transport

| ID | Requirement | Priority |
|---|---|---|
| F-10 | Encode an utterance into a self-describing binary packet (see TRD §4) | P0 |
| F-11 | A typical 20-word sentence must encode to ≤ 96 bytes total | P0 |
| F-12 | Detect corrupted packets and discard them | P0 |
| F-13 | Send and receive over Bluetooth RFCOMM between two phones | P0 |
| F-14 | Send and receive over Wi-Fi Direct between two phones | P1 |
| F-15 | Transport must be swappable without touching speech code | P0 |
| F-16 | Optional forward error correction and repeat-send for lossy links | P2 |

### 7.3 Synthesis and playback

| ID | Requirement | Priority |
|---|---|---|
| F-20 | Decode a received packet and speak it aloud on-device | P0 |
| F-21 | Begin audio playback before the whole utterance is synthesised | P1 |
| F-22 | Render the received message in the receiver's own script when displayed | P1 |
| F-23 | Reproduce the sender's voice character from the identity code | P2 |
| F-24 | Apply received pitch/rate/energy to the synthesised voice | P2 |

### 7.4 Alerting

| ID | Requirement | Priority |
|---|---|---|
| F-30 | Every message carries an urgency level: ROUTINE, ALERT, or DISTRESS | P0 |
| F-31 | DISTRESS plays at maximum device volume, overriding the user's volume setting | P0 |
| F-32 | DISTRESS playback cannot be interrupted by the user or by another message | P0 |
| F-33 | DISTRESS ignores silent mode and Do Not Disturb | P1 |
| F-34 | The user can set urgency manually; the system may also detect it from the voice | P1 → P2 |

### 7.5 Languages

| ID | Requirement | Priority |
|---|---|---|
| F-40 | The app ships with **no** language built in; languages are drop-in packs | P0 |
| F-41 | The available-language list is read from a manifest file at runtime | P0 |
| F-42 | Adding a language must require zero code changes and zero recompilation | P0 |
| F-43 | English + one Indic language fully working end-to-end | P0 |
| F-44 | A third language added live on stage in under 60 seconds | P1 |
| F-45 | Language identifiers are fixed permanently (see TRD §4.3) | P0 |

### 7.6 Measurement (this is a feature, not a chore)

| ID | Requirement | Priority |
|---|---|---|
| F-50 | In-app display of: end-to-end latency, packet size, recognition time, synthesis time | P0 |
| F-51 | Export a CSV of every measured transmission | P1 |
| F-52 | A bandwidth-throttling mode that simulates a 300 bps – 2 kbps link | **P0** |
| F-53 | Report app size, model sizes and idle CPU on screen | P1 |

## 8. Success metrics

Mapped directly to the evaluation rubric in the problem statement.

| Rubric item | Weight | Our target | How measured |
|---|---|---|---|
| **Accuracy** — STT word error rate | 40% | ≤ 15% English, ≤ 25% Indic on held-out set | Scored against a labelled test set on-device |
| **Accuracy** — TTS intelligibility | (same 40%) | ≥ 4.0 / 5.0 mean opinion score | Panel of 10 native listeners, 20 sentences |
| **Efficiency** — installed size | 20% | ≤ 150 MB with 2 language packs | Measured APK + pack size |
| **Efficiency** — idle CPU | (same 20%) | ≤ 5% on a 4-core low-end device | Android Profiler / Perfetto trace |
| **Efficiency** — peak RAM | (same 20%) | ≤ 400 MB | Profiler |
| **Latency** — mouth to ear | 20% | ≤ 1000 ms for a 3-second utterance | Shared clock, logged on both devices |
| **Latency** — real-time factor | (same 20%) | STT ≤ 0.4, TTS ≤ 0.5 | Timed on-device |

> **Note on the rubric:** the published weights total 80%. The remaining 20% is unstated — we assume
> it covers system design, completeness and presentation, and we treat the demonstration and the
> documentation as scored work rather than as an afterthought.

**Our own additional metric, because it is our differentiator:**

| Metric | Target |
|---|---|
| Bytes on the wire per 3-second utterance | ≤ 96 bytes (vs ~6,000 for a compressed voice note) |
| Compression advantage over Opus voice | ≥ 60× |
| Message survives a 1 kbps link | Yes, where a voice note fails entirely |

## 9. Constraints (non-negotiable)

| ID | Constraint |
|---|---|
| C-01 | No proprietary or commercial speech SDK. Open-source licences only. |
| C-02 | No internet API for any speech processing, ever. |
| C-03 | Frameworks limited to open-source ML/TinyML runtimes (ONNX Runtime, TFLite, PyTorch Mobile or equivalent). |
| C-04 | Must run acceptably on a 4-core, 3 GB RAM, no-NPU Android device. |
| C-05 | Minimum Android 7.0 (API 24). |

### 9.1 On the phrase "fully offline"

Language packs are downloaded once, at install time, over any available connection — exactly as
firmware and map data are provisioned into field equipment. **All speech processing is permanently
on-device with no network dependency at any point during operation.** The app must run correctly in
airplane mode from first launch onward. This position is stated openly in the submission rather than
hidden; a rescue worker in Odisha does not carry Malayalam models they will never use.

## 10. Acceptance criteria — the demonstration

The build is complete when all of the following can be performed on demand:

1. **The loop.** Two phones, no network of any kind (airplane mode, Bluetooth only). Person A holds
   PTT and speaks a sentence. Person B hears that sentence spoken aloud. Latency displayed on screen
   is under one second.
2. **The size claim.** The on-screen counter shows the transmission was under 100 bytes.
3. **The narrow-link proof.** Throttle mode set to 1 kbps. A voice note of the same sentence is
   attempted over the same throttled channel and fails or stalls. iTantra delivers the same sentence
   cleanly. Shown side by side.
4. **The distress path.** A DISTRESS message is sent to a phone that is on silent with the volume at
   zero. It plays at full volume and cannot be silenced until it finishes.
5. **The scaling claim.** A third language pack is copied onto the device and appears in the language
   list within 60 seconds, with no rebuild.
6. **The numbers.** A CSV of at least 50 measured transmissions is produced, showing latency, packet
   size and RTF distributions — not single cherry-picked runs.

## 11. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Indic model conversion to ONNX fails | Project has no Indic language | Start conversion in week 1, not week 3; keep a pre-converted community model as fallback |
| Phoneme-target recognition loses word boundaries | Cannot display readable text | Reserve an explicit word-boundary symbol in the codec from day one (TRD §4.4) |
| 10 languages not achievable in the time available | Looks incomplete | Ship 2–3 excellently, prove the scaling mechanism live. State this openly and early |
| Bluetooth pairing unreliable on demo day | Demo fails on stage | Loopback transport and a pre-paired backup device pair; rehearse 20 times |
| Low-end device too slow for real-time | Fails latency and efficiency | Quantise all models to int8; measure on the actual target device from week 1, never on a flagship |

## 12. Release plan

| Milestone | Contents | Gate |
|---|---|---|
| **M0 — Skeleton** | App builds, installs, runs, PTT button present | `adb install` succeeds on a real phone |
| **M1 — Dumb loop** | Bytes travel phone→phone over Bluetooth on button press | Second phone displays received bytes |
| **M2 — Audio loop** | Record and play raw audio through the transport | Voice heard on the other phone |
| **M3 — Speech loop** | English STT → packet → TTS across the link | Sentence spoken on A is heard on B |
| **M4 — Wire format** | Full packet spec, urgency, prosody, CRC, metrics HUD | Under 100 bytes on screen |
| **M5 — Indic** | Language pack system + one Indic language | Language switch works, no rebuild |
| **M6 — Proof** | Throttle demo, CSV export, WER harness, distress path | All six acceptance criteria pass |

---

*Next document: `TRD.md` — how each of these requirements is built.*
