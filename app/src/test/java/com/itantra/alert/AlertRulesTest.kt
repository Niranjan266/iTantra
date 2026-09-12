package com.itantra.alert

import com.itantra.codec.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These rules let the app override the user's own volume and silent-mode settings. That
 * power needs to be exactly as wide as the requirement and no wider, so every boundary
 * is pinned here.
 */
class AlertRulesTest {

    @Test
    fun `distress plays at maximum volume on the alarm stream`() {
        assertEquals(AlertRules.Stream.ALARM, AlertRules.streamFor(Intent.DISTRESS))
        assertEquals(1.0, AlertRules.minimumVolumeFraction(Intent.DISTRESS)!!, 0.0001)
    }

    @Test
    fun `distress cannot be interrupted by anything`() {
        assertTrue(!AlertRules.isInterruptible(Intent.DISTRESS))
        // Not even by another distress message.
        assertTrue(!AlertRules.shouldPreempt(Intent.DISTRESS, Intent.DISTRESS))
        assertTrue(!AlertRules.shouldPreempt(Intent.ALERT, Intent.DISTRESS))
        assertTrue(!AlertRules.shouldPreempt(Intent.ROUTINE, Intent.DISTRESS))
    }

    @Test
    fun `distress overrides silent mode but nothing else does`() {
        assertTrue(AlertRules.overridesSilentMode(Intent.DISTRESS))
        assertTrue(!AlertRules.overridesSilentMode(Intent.ALERT))
        assertTrue(!AlertRules.overridesSilentMode(Intent.ROUTINE))
    }

    @Test
    fun `routine traffic never touches the users volume`() {
        assertNull(AlertRules.minimumVolumeFraction(Intent.ROUTINE))
        assertEquals(AlertRules.Stream.MEDIA, AlertRules.streamFor(Intent.ROUTINE))
        assertTrue(!AlertRules.mustRestoreVolume(Intent.ROUTINE))
    }

    @Test
    fun `alert is loud but still interruptible`() {
        assertEquals(0.7, AlertRules.minimumVolumeFraction(Intent.ALERT)!!, 0.0001)
        assertTrue(AlertRules.isInterruptible(Intent.ALERT))
    }

    @Test
    fun `a more urgent message preempts a less urgent one`() {
        assertTrue(AlertRules.shouldPreempt(Intent.DISTRESS, Intent.ROUTINE))
        assertTrue(AlertRules.shouldPreempt(Intent.DISTRESS, Intent.ALERT))
        assertTrue(AlertRules.shouldPreempt(Intent.ALERT, Intent.ROUTINE))
    }

    @Test
    fun `an equal or less urgent message waits its turn`() {
        assertTrue(!AlertRules.shouldPreempt(Intent.ROUTINE, Intent.ROUTINE))
        assertTrue(!AlertRules.shouldPreempt(Intent.ROUTINE, Intent.ALERT))
        assertTrue(!AlertRules.shouldPreempt(Intent.ALERT, Intent.ALERT))
    }

    @Test
    fun `anything that raises the volume also restores it`() {
        for (intent in listOf(Intent.ROUTINE, Intent.ALERT, Intent.DISTRESS)) {
            val raises = AlertRules.minimumVolumeFraction(intent) != null
            assertEquals(
                "intent $intent raises=$raises but restore=${AlertRules.mustRestoreVolume(intent)}",
                raises,
                AlertRules.mustRestoreVolume(intent),
            )
        }
    }

    @Test
    fun `urgency is monotonic - louder and less interruptible as it rises`() {
        val levels = listOf(Intent.ROUTINE, Intent.ALERT, Intent.DISTRESS)
        val volumes = levels.map { AlertRules.minimumVolumeFraction(it) ?: 0.0 }
        assertEquals(volumes.sorted(), volumes)
        // Interruptibility must never increase with urgency.
        val interruptible = levels.map { AlertRules.isInterruptible(it) }
        assertEquals(listOf(true, true, false), interruptible)
    }
}
