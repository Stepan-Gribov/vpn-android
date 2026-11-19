package com.v2ray.ang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionFallbackPlanTest {
    @Test
    fun `uses the displayed order and advances one profile at a time`() {
        val plan = ConnectionFallbackPlan.create(listOf("first", "second", "third"))!!

        assertEquals("first", plan.currentGuid)
        assertEquals(1, plan.attemptNumber)
        assertEquals("second", plan.advance())
        assertEquals(2, plan.attemptNumber)
        assertEquals("third", plan.advance())
        assertEquals(3, plan.attemptNumber)
        assertNull(plan.advance())
        assertEquals("third", plan.currentGuid)
    }

    @Test
    fun `drops blank and duplicate candidates`() {
        val plan = ConnectionFallbackPlan.create(listOf("", "first", "first", "second"))!!

        assertEquals(2, plan.size)
        assertEquals("first", plan.currentGuid)
        assertEquals("second", plan.advance())
    }

    @Test
    fun `does not create an empty plan`() {
        assertNull(ConnectionFallbackPlan.create(listOf("", "")))
    }

    @Test
    fun `smart plan starts with fastest success and keeps rollover groups stable`() {
        val plan = ConnectionFallbackPlan.createFastest(
            listOf(
                ConnectionFallbackPlan.Candidate("failed-first", -1),
                ConnectionFallbackPlan.Candidate("untested-first", 0),
                ConnectionFallbackPlan.Candidate("slow", 240),
                ConnectionFallbackPlan.Candidate("fast", 80),
                ConnectionFallbackPlan.Candidate("untested-second", 0),
                ConnectionFallbackPlan.Candidate("failed-second", -1),
            ),
        )!!

        assertEquals("fast", plan.currentGuid)
        assertEquals("slow", plan.advance())
        assertEquals("untested-first", plan.advance())
        assertEquals("untested-second", plan.advance())
        assertEquals("failed-first", plan.advance())
        assertEquals("failed-second", plan.advance())
    }

    @Test
    fun `smart plan excludes candidates that are not eligible for automatic connect`() {
        val plan = ConnectionFallbackPlan.createFastest(
            listOf(
                ConnectionFallbackPlan.Candidate(
                    guid = "vk-turn",
                    delayMillis = 20,
                    automaticEligible = false,
                ),
                ConnectionFallbackPlan.Candidate("regular-fast", 50),
                ConnectionFallbackPlan.Candidate("regular-slow", 100),
            ),
        )!!

        assertEquals(2, plan.size)
        assertEquals("regular-fast", plan.currentGuid)
        assertEquals("regular-slow", plan.advance())
        assertNull(plan.advance())
    }
}
