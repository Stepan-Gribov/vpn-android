package com.v2ray.ang.core

import org.junit.Assert.*
import org.junit.Test

class AmneziaNetworkSelectionTest {
    @Test fun newUnvalidatedWifiCannotReplaceWorkingRoute() {
        val selection = AmneziaNetworkSelection<Int>()
        assertEquals(1, selection.available(1, true))
        assertNull(selection.available(2, true))
        assertEquals(2, selection.validated(2))
        assertNull(selection.validated(2))
    }

    @Test fun restrictedNetworkCanStartWithoutAndroidValidation() {
        val selection = AmneziaNetworkSelection<Int>()
        assertEquals(1, selection.available(1, true))
        assertNull(selection.failed())
    }

    @Test fun realFailureCanTryRestrictedCandidateWithoutValidation() {
        val selection = AmneziaNetworkSelection<Int>()
        selection.available(1, true)
        assertNull(selection.available(2, true))
        assertEquals(2, selection.failed())
        assertNull(selection.failed())
    }

    @Test fun lostSelectedNetworkDoesNotWaitIndefinitelyForValidation() {
        val selection = AmneziaNetworkSelection<Int>()
        selection.available(1, true); selection.available(2, true)
        assertEquals(2, selection.lost(1))
        assertNull(selection.failed())
    }

    @Test fun staleValidationOrLostCandidateCannotChangeCurrentRoute() {
        val selection = AmneziaNetworkSelection<Int>()
        selection.available(1, true); selection.available(2, true); selection.available(3, true)
        assertNull(selection.validated(2))
        assertNull(selection.lost(3))
        assertNull(selection.validated(3))
        assertEquals("A still available earlier candidate can be tried", 2, selection.failed())
    }

    @Test fun failedCandidateDisappearingReturnsToPreviousLiveNetwork() {
        val selection = AmneziaNetworkSelection<Int>()
        selection.available(1, true); selection.available(2, true)
        assertEquals(2, selection.failed())
        assertEquals(1, selection.lost(2))
        assertNull(selection.failed())
    }

    @Test fun lostOldNetworkIsNeverReusedAsFallback() {
        val selection = AmneziaNetworkSelection<Int>()
        selection.available(1, true); selection.available(2, true)
        assertEquals(2, selection.validated(2))
        assertNull(selection.lost(1))
        assertNull(selection.lost(2))
        assertNull(selection.failed())
    }

    @Test fun stopDropsCandidateAndNonAmneziaBehaviorIsUnchanged() {
        val selection = AmneziaNetworkSelection<Int>()
        selection.available(1, true); selection.available(2, true); selection.reset()
        assertNull(selection.validated(2)); assertNull(selection.failed())
        assertEquals(3, selection.available(3, false))
        assertEquals(4, selection.available(4, false))
    }
}
