package com.example.myapplication.movecoach

import com.example.ondeviceai.AiRoutePolicyDecider.FallbackReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * B17. These pin the *product* decisions, not the mapping's mechanics — a future contributor who
 * decides quota should be silent, or that thermal deserves a banner, has to change a test that says
 * so out loud rather than discovering the regression on a demo device.
 */
class FallbackPresentationTest {

    @Test
    fun `CRITICAL thermal degrades to the deterministic line - never a dead panel`() {
        // The plan's one hard guarantee: AiRoutePolicyDecider maps CRITICAL thermal to
        // FallbackReason.Thermal, which must render as ordinary coach text with no error chrome.
        assertEquals(FallbackPresentation.Silent, FallbackPresentation.of(FallbackReason.Thermal))
    }

    @Test
    fun `substitutions that are as good as the real thing are silent`() {
        listOf(
            FallbackReason.NoLocalModel,
            FallbackReason.NoNetwork,
            FallbackReason.NoRoute,
            FallbackReason.Background,
            FallbackReason.Validation,
            FallbackReason.Other("generation error: boom"),
        ).forEach { reason ->
            assertEquals(
                FallbackPresentation.Silent,
                FallbackPresentation.of(reason),
                "expected $reason to substitute silently",
            )
        }
    }

    @Test
    fun `quota is labeled because waiting changes the outcome`() {
        val presentation = assertIs<FallbackPresentation.Labeled>(
            FallbackPresentation.of(FallbackReason.Quota),
        )
        assertEquals(FallbackPresentation.BUSY_LABEL, presentation.label)
    }

    @Test
    fun `timeout offers a retry`() {
        val presentation = assertIs<FallbackPresentation.Retryable>(
            FallbackPresentation.of(FallbackReason.Timeout),
        )
        assertEquals(FallbackPresentation.SLOW_LABEL, presentation.label)
    }

    @Test
    fun `no reason maps to a blank presentation`() {
        // Every branch must produce something renderable; a null/blank label with no text is the
        // "dead panel" B17 exists to prevent.
        listOf(
            FallbackReason.NoLocalModel,
            FallbackReason.NoNetwork,
            FallbackReason.NoRoute,
            FallbackReason.Background,
            FallbackReason.Thermal,
            FallbackReason.Validation,
            FallbackReason.Quota,
            FallbackReason.Timeout,
            FallbackReason.Other("x"),
        ).forEach { reason ->
            when (val presentation = FallbackPresentation.of(reason)) {
                FallbackPresentation.Silent -> Unit
                is FallbackPresentation.Labeled -> assertEquals(true, presentation.label.isNotBlank())
                is FallbackPresentation.Retryable -> assertEquals(true, presentation.label.isNotBlank())
            }
        }
    }
}
