package com.shilapi.xcertplay.network

import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayVpnAttachDiagnosticsTest {
    @Test fun preservesExceptionTypeNestedCauseAndSource() {
        val failure = IllegalArgumentException("Invalid argument", IllegalStateException("netd rejected"))
        val text = CarPlayVpnService.attachFailure(failure)
        assertTrue(text, text.contains("IllegalArgumentException: Invalid argument"))
        assertTrue(text, text.contains("IllegalStateException: netd rejected"))
        assertTrue(text, text.contains(" at "))
    }
}