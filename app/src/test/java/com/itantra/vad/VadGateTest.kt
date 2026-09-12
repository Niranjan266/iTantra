package com.itantra.vad

import com.itantra.audio.AudioSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

/**
 * Endpointing decides when someone has stopped talking, and getting it wrong is
 * expensive in both directions: too eager and it cuts people off mid-sentence, too slow
 * and every message feels sluggish. Neither failure is pleasant to chase on a phone in
 * a noisy room, so the logic is pinned here with synthetic audio.
 */
class VadGateTest {

    private val rng = Random(1234)

    /** A frame of room tone at the given RMS level. */
    private fun noise(level: Double): ShortArray =
        ShortArray(AudioSpec.SAMPLES_PER_FRAME) {
            (rng.nextDouble(-1.0, 1.0) * level * 1.732).toInt().toShort()
        }

    /** A frame of tone, standing in for voiced speech. */
    private fun tone(amplitude: Double, freq: Double = 200.0): ShortArray =
        ShortArray(AudioSpec.SAMPLES_PER_FRAME) { i ->
            (amplitude * sin(2 * Math.PI * freq * i / AudioSpec.SAMPLE_RATE)).toInt().toShort()
        }

    private fun feed(gate: VadGate, frame: ShortArray, count: Int): List<VadGate.Event> =
        (0 until count).mapNotNull { gate.accept(frame) }

    @Test
    fun `silence alone never triggers speech`() {
        val gate = VadGate()
        val events = (0 until 200).mapNotNull { gate.accept(noise(80.0)) }
        assertTrue("silence produced $events", events.isEmpty())
        assertTrue(!gate.isSpeaking)
    }

    @Test
    fun `loud speech after quiet triggers exactly one start`() {
        val gate = VadGate()
        feed(gate, noise(80.0), 50)
        val events = feed(gate, tone(6000.0), 50)
        assertEquals(1, events.count { it is VadGate.Event.SpeechStart })
    }

    @Test
    fun `speech then silence produces one end after the hangover`() {
        val gate = VadGate()
        feed(gate, noise(80.0), 50)
        feed(gate, tone(6000.0), 40)          // ~800 ms of speech
        val events = feed(gate, noise(80.0), 60)

        val ends = events.filterIsInstance<VadGate.Event.SpeechEnd>()
        assertEquals(1, ends.size)
        assertEquals(500, ends.first().endpointDelayMs)
        assertTrue("speech duration was ${ends.first().speechDurationMs}",
            ends.first().speechDurationMs >= 800)
    }

    @Test
    fun `a short pause between words does not end the utterance`() {
        val gate = VadGate()
        feed(gate, noise(80.0), 50)
        feed(gate, tone(6000.0), 20)
        // 200 ms gap — well inside the 500 ms hangover.
        val duringGap = feed(gate, noise(80.0), AudioSpec.framesForMs(200))
        assertTrue("ended during a word gap: $duringGap",
            duringGap.filterIsInstance<VadGate.Event.SpeechEnd>().isEmpty())
        assertTrue(gate.isSpeaking)

        feed(gate, tone(6000.0), 20)
        val ends = feed(gate, noise(80.0), 60).filterIsInstance<VadGate.Event.SpeechEnd>()
        assertEquals("should end once, after the real stop", 1, ends.size)
    }

    @Test
    fun `endpoint delay matches the configured hangover`() {
        for (hangover in listOf(200, 300, 500, 800)) {
            val gate = VadGate(VadGate.Config(hangoverMs = hangover))
            feed(gate, noise(80.0), 50)
            feed(gate, tone(6000.0), 30)
            val ends = feed(gate, noise(80.0), AudioSpec.framesForMs(hangover) + 10)
                .filterIsInstance<VadGate.Event.SpeechEnd>()
            assertEquals("hangover $hangover", 1, ends.size)
            assertEquals(hangover, ends.first().endpointDelayMs)
        }
    }

    @Test
    fun `a noisy room does not trigger speech once the floor has adapted`() {
        // Loud background — a relief camp, not a lab.
        val gate = VadGate()
        val events = (0 until 400).mapNotNull { gate.accept(noise(900.0)) }
        assertTrue("steady loud noise was treated as speech: $events", events.isEmpty())
    }

    @Test
    fun `speech is still detected over a noisy room`() {
        val gate = VadGate()
        feed(gate, noise(900.0), 300)   // let the floor rise to match the room
        val events = feed(gate, tone(12000.0), 40)
        assertEquals("speech missed in a noisy room", 1,
            events.count { it is VadGate.Event.SpeechStart })
    }

    @Test
    fun `the noise floor does not drift upward during a long utterance`() {
        val gate = VadGate()
        feed(gate, noise(80.0), 50)
        val floorBefore = gate.noiseFloor
        feed(gate, tone(9000.0), 250)   // 5 seconds of continuous speech
        assertTrue(
            "floor climbed during speech: $floorBefore -> ${gate.noiseFloor}",
            gate.noiseFloor <= floorBefore * 1.5,
        )
        assertTrue("speaker cut themselves off", gate.isSpeaking)
    }

    @Test
    fun `the floor does not ratchet upward over many utterances`() {
        // The failure this guards against is slow: each utterance nudges the floor up
        // a little, and after a long session real speech no longer clears it.
        val gate = VadGate()
        feed(gate, noise(80.0), 100)
        val floorAtStart = gate.noiseFloor
        repeat(20) {
            feed(gate, tone(7000.0), 25)
            feed(gate, noise(80.0), 40)
        }
        assertTrue(
            "floor ratcheted from $floorAtStart to ${gate.noiseFloor} over 20 utterances",
            gate.noiseFloor <= floorAtStart * 1.2,
        )
        // And it must still hear the next one.
        val events = feed(gate, tone(7000.0), 25)
        assertEquals(1, events.count { it is VadGate.Event.SpeechStart })
    }

    @Test
    fun `sustained loud noise releases instead of latching on forever`() {
        val gate = VadGate(VadGate.Config(maxUtteranceMs = 1_000))
        feed(gate, noise(80.0), 50)
        val events = feed(gate, tone(9000.0), AudioSpec.framesForMs(3_000))
        assertTrue(
            "detector latched on and never released",
            events.filterIsInstance<VadGate.Event.SpeechEnd>().isNotEmpty(),
        )
    }

    @Test
    fun `a single loud click does not trigger speech`() {
        val gate = VadGate()
        feed(gate, noise(80.0), 50)
        val events = listOfNotNull(gate.accept(tone(20000.0))) +
            feed(gate, noise(80.0), 30)
        assertTrue("a one-frame transient triggered speech: $events",
            events.filterIsInstance<VadGate.Event.SpeechStart>().isEmpty())
    }

    @Test
    fun `forceEnd closes an active utterance with no endpoint delay`() {
        val gate = VadGate()
        feed(gate, noise(80.0), 50)
        feed(gate, tone(6000.0), 30)
        assertTrue(gate.isSpeaking)

        val end = gate.forceEnd()
        assertNotNull(end)
        assertEquals(0, end!!.endpointDelayMs)
        assertTrue(end.speechDurationMs > 0)
        assertTrue(!gate.isSpeaking)
    }

    @Test
    fun `forceEnd when not speaking returns nothing`() {
        val gate = VadGate()
        feed(gate, noise(80.0), 20)
        assertNull(gate.forceEnd())
    }

    @Test
    fun `reset returns the gate to its initial state`() {
        val gate = VadGate()
        feed(gate, noise(80.0), 30)
        feed(gate, tone(9000.0), 20)
        assertTrue(gate.isSpeaking)
        gate.reset()
        assertTrue(!gate.isSpeaking)
        assertEquals(0.0, gate.peakRms, 0.001)
    }

    @Test
    fun `start and end alternate strictly over several utterances`() {
        val gate = VadGate()
        val events = mutableListOf<VadGate.Event>()
        feed(gate, noise(80.0), 50)
        repeat(3) {
            events += feed(gate, tone(7000.0), 25)
            events += feed(gate, noise(80.0), 40)
        }
        // Must be START, END, START, END, START, END — never two of a kind in a row.
        assertEquals(6, events.size)
        events.forEachIndexed { i, e ->
            if (i % 2 == 0) assertTrue("index $i should be a start", e is VadGate.Event.SpeechStart)
            else assertTrue("index $i should be an end", e is VadGate.Event.SpeechEnd)
        }
    }

    @Test
    fun `rms is correct for known signals`() {
        assertEquals(0.0, VadGate.rms(ShortArray(320)), 0.001)
        val flat = ShortArray(320) { 1000 }
        assertEquals(1000.0, VadGate.rms(flat), 0.001)
        assertEquals(0.0, VadGate.rms(ShortArray(0)), 0.001)
    }

    @Test
    fun `peak level tracks the loudest frame`() {
        val gate = VadGate()
        gate.accept(noise(100.0))
        val quietPeak = gate.peakRms
        gate.accept(tone(15000.0))
        assertTrue(gate.peakRms > quietPeak * 5)
    }
}
