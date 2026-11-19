package com.v2ray.ang.core

import org.junit.Assert.*
import org.junit.Test

class TurnCaptchaStateTest {
    @Test fun `human fallback survives the former automatic restart without extending the challenge`() {
        val state = TurnCaptchaState()
        val start = state.begin(1_000, automatic = true)
        assertTrue(state.requireUser(start.id))
        val manual = state.snapshot()!!
        assertTrue(manual.needsUser)
        assertFalse(manual.shouldHandOff(46_000))
        assertFalse(manual.expired(46_000))
        assertFalse(manual.expired(300_999))
        assertTrue(manual.expired(301_000))
    }

    @Test fun `automatic deadline requests handoff rather than expiring the native challenge`() {
        val challenge = TurnCaptchaState().begin(1_000, automatic = true)
        assertFalse(challenge.shouldHandOff(45_999))
        assertTrue(challenge.shouldHandOff(46_000))
        assertFalse(challenge.expired(46_000))
    }

    @Test fun `duplicate markers and repeated user handoff preserve the original deadline`() {
        val state = TurnCaptchaState()
        val initial = state.begin(10_000, automatic = true)
        state.requireUser(initial.id)
        val repeated = state.begin(250_000, automatic = true)
        assertEquals(initial.id, repeated.id)
        assertEquals(initial.startedAtMillis, repeated.startedAtMillis)
        assertTrue(repeated.needsUser)
        state.requireUser(initial.id)
        assertTrue(state.snapshot()!!.expired(310_000))
    }

    @Test fun `late callbacks cannot revive a completed or stopped challenge or change its replacement`() {
        val state = TurnCaptchaState()
        val old = state.begin(1_000, automatic = true)
        state.clear()
        assertFalse(state.requireUser(old.id))
        assertNull(state.snapshot())
        val replacement = state.begin(2_000, automatic = true)
        assertNotEquals(old.id, replacement.id)
        assertFalse(state.requireUser(old.id))
        assertEquals(replacement, state.snapshot())
    }

    @Test fun `explicit manual mode uses the same finite deadline from the start`() {
        val challenge = TurnCaptchaState().begin(1_000, automatic = false)
        assertTrue(challenge.needsUser)
        assertFalse(challenge.shouldHandOff(46_000))
        assertTrue(challenge.expired(301_000))
    }
}
