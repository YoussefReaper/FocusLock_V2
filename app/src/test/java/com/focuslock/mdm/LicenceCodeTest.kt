package com.focuslock.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline verification of FocusLock Pro licence codes.
 *
 * The fixtures were signed by the fulfillment server's real issuer
 * (licence.js) with a throwaway test keypair, so these tests prove the two
 * sides agree on the format without putting a working production code in
 * the repository.
 */
class LicenceCodeTest {

    private val testKey = LicenceVerifier.decodeKey(
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE6OaZ6HSWKR5xjGVj/YkLWDmo2d4eX2x+txt0Y8lPkWBKS97svd127Wqpd/6I9VOWX7aOoBdhqRixSqCLwbI7bg=="
    )
    private val codeA = "FL2-AEAUQ-N9YFG-2UZDS-ASPDK-ZUDMK-SBM83-6P48X-345C2-54GBX-8LKTX-ZJYVQ-YPSMX-SEVND-T3CSF-7K6SQ-DUT5E-BPRYD-DQ7UM-3BTED-GFLGQ-PE9LY-89VF8-LAF79-SRQA"
    private val codeB = "FL2-AEAUQ-QS9Q4-5YQAA-AHYQ8-BYYD2-Y9L3R-4CW7L-QNYQH-5ZBRN-93PE8-7W6X9-K65UF-8KSEL-CGUBZ-QQGD5-PUUMN-G24YU-JS44L-T4ENY-ZB8AD-9ZUS4-VXB3R-CHL5U-VKFS"

    /** Same payload as [codeA], signed by a different private key. */
    private val forged = "FL2-AEAUQ-N9YFG-2UZDS-ADWZE-7EKBN-6SW8S-83YHT-FAQ3M-5LCXZ-BKQ4C-LQAM6-QTVLQ-ZFE6G-YWTE7-S9MJA-B4H3B-FQJKK-U9W3A-ZAWXL-RSVRH-EDXPC-ZZPYQ-WACDP-B2K2"

    @Test
    fun serverSignedCodesVerify() {
        assertTrue(LicenceVerifier.verify(codeA, testKey))
        assertTrue(LicenceVerifier.verify(codeB, testKey))
    }

    @Test
    fun messyPastesAndDeepLinksStillVerify() {
        assertTrue(LicenceVerifier.verify("  " + codeA.lowercase().replace("-", " ") + "\n", testKey))
        assertTrue(LicenceVerifier.verify("focuslock://pro/$codeB", testKey))
        assertEquals(codeA, LicenceVerifier.format(codeA.replace("-", "")))
    }

    @Test
    fun codeSignedByAnotherKeyIsRejected() {
        assertFalse(LicenceVerifier.verify(forged, testKey))
    }

    @Test
    fun anySingleCharacterChangeIsRejected() {
        val body = codeA.removePrefix("FL2-")
        for (i in body.indices) {
            if (body[i] == '-') continue
            val swapped = if (body[i] == 'A') 'B' else 'A'
            val tampered = "FL2-" + body.substring(0, i) + swapped + body.substring(i + 1)
            assertFalse("position $i", LicenceVerifier.verify(tampered, testKey))
        }
    }

    @Test
    fun malformedInputIsRejected() {
        assertFalse(LicenceVerifier.verify(null, testKey))
        assertFalse(LicenceVerifier.verify("", testKey))
        assertFalse(LicenceVerifier.verify("FREE-PRO-UNLOCK", testKey))
        assertFalse(LicenceVerifier.verify("FL2-AAAA-BBBB-CCCC-DDDD", testKey))
        assertFalse(LicenceVerifier.verify(codeA.dropLast(5), testKey))
        assertNull(LicenceVerifier.format("FL2-AAAA"))
        assertNotNull(LicenceVerifier.format(codeB))
    }

    @Test
    fun productionKeyDoesNotAcceptTestCodes() {
        // The shipped key and the test key are different, so these fixtures
        // can never unlock a real install.
        assertFalse(DemoMode.verifyLicenceCode(codeA))
    }
}
