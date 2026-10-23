package com.focuslock.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline verification tests for FocusLock V2 Pro licence codes.
 */
class LicenceCodeTest {

    @Test
    fun validSignedCodePassesVerification() {
        val payload = "A7K9M2P4Q8W3"
        val sig = DemoMode.computeSignatureTag(payload)
        val raw = "FL2-$payload-$sig"
        val formatted = DemoMode.normalizeAndFormat(raw)
        assertNotNull(formatted)
        assertTrue(DemoMode.verifyLicenceCode(formatted))
    }

    @Test
    fun lowercaseAndSpacedValidCodeIsAccepted() {
        val payload = "B3N6R9T2V5X8"
        val sig = DemoMode.computeSignatureTag(payload)
        val messy = "  fl2 ${payload.lowercase()} ${sig.lowercase()}  "
        assertTrue(DemoMode.verifyLicenceCode(messy))
        assertEquals("FL2-B3N6-R9T2-V5X8-$sig", DemoMode.normalizeAndFormat(messy))
    }

    @Test
    fun tamperedPayloadOrSignatureIsRejected() {
        val payload = "A7K9M2P4Q8W3"
        val sig = DemoMode.computeSignatureTag(payload)
        // Tamper with one character in the payload
        val tamperedPayload = "FL2-B7K9-M2P4-Q8W3-$sig"
        assertFalse(DemoMode.verifyLicenceCode(tamperedPayload))

        // Tamper with signature
        val wrongSig = if (sig.startsWith("A")) "B" + sig.substring(1) else "A" + sig.substring(1)
        val tamperedSig = "FL2-A7K9-M2P4-Q8W3-$wrongSig"
        assertFalse(DemoMode.verifyLicenceCode(tamperedSig))
    }

    @Test
    fun blankRandomOrMalformedCodesAreRejected() {
        assertFalse(DemoMode.verifyLicenceCode(null))
        assertFalse(DemoMode.verifyLicenceCode(""))
        assertFalse(DemoMode.verifyLicenceCode("   "))
        assertFalse(DemoMode.verifyLicenceCode("FREE-PRO-UNLOCK"))
        assertFalse(DemoMode.verifyLicenceCode("FL2-AAAA-BBBB-CCCC-DDDD"))
        assertFalse(DemoMode.verifyLicenceCode("FL1-A7K9-M2P4-Q8W3-AAAA"))
    }
}
