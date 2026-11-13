package com.focuslock.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeeperPlusLogicTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    // ── proxy response → what happens next ──

    @Test fun okReplyIsAnswered() {
        val p = KeeperRouting.mapProxy(200, """{"ok":true,"reply":"{\"verdict\":\"allow\"}","usedToday":3,"limit":200}""")
        assertEquals(KeeperRouting.Proxy.Reply("{\"verdict\":\"allow\"}", 3, 200), p)
    }

    @Test fun okWithoutReplyIsUnreadable() =
        assertEquals(KeeperRouting.Proxy.Fail(KeeperResult.Why.UNREADABLE), KeeperRouting.mapProxy(200, "{\"ok\":true}"))

    @Test fun dailyLimitIsItsOwnReason() =
        assertEquals(KeeperRouting.Proxy.Fail(KeeperResult.Why.DAILY_LIMIT, 200),
            KeeperRouting.mapProxy(429, """{"ok":false,"reason":"daily_limit","limit":200}"""))

    @Test fun notConfiguredFallsBack() {
        assertEquals(KeeperRouting.Proxy.Fallback, KeeperRouting.mapProxy(503, """{"ok":false,"reason":"accounts_not_configured"}"""))
        assertEquals(KeeperRouting.Proxy.Fallback, KeeperRouting.mapProxy(503, """{"ok":false,"reason":"keeper_not_configured"}"""))
        assertEquals(KeeperRouting.Proxy.Fallback, KeeperRouting.mapProxy(-1, "{}"))
        assertEquals(KeeperRouting.Proxy.Fallback, KeeperRouting.mapProxy(403, """{"ok":false,"reason":"no_keeper_plus"}"""))
    }

    @Test fun upstreamFailureIsNetwork() =
        assertEquals(KeeperRouting.Proxy.Fail(KeeperResult.Why.NETWORK), KeeperRouting.mapProxy(502, """{"ok":false,"reason":"upstream_failed"}"""))

    @Test fun imageLimitMatchesServer() {
        assertFalse(KeeperRouting.imageFits(null))
        assertFalse(KeeperRouting.imageFits("data:image/png;base64,AAAA"))
        assertTrue(KeeperRouting.imageFits(KeeperRouting.IMAGE_PREFIX + "A".repeat(2_097_152)))   // exactly 1.5 MiB
        assertFalse(KeeperRouting.imageFits(KeeperRouting.IMAGE_PREFIX + "A".repeat(2_097_156)))
    }

    // ── entitlement expiry and offline grace ──

    @Test fun permanentEntitlementHoldsWithinGrace() =
        assertTrue(EntitlementRules.isActive(Entitlement(true, null), now - 6 * day, now))

    @Test fun offlineLongerThanSevenDaysStopsCounting() =
        assertFalse(EntitlementRules.isActive(Entitlement(true, null), now - 7 * day - 1, now))

    @Test fun expiredEntitlementStops() =
        assertFalse(EntitlementRules.isActive(Entitlement(true, now - 1), now - day, now))

    @Test fun inactiveOrUnknownNeverCounts() {
        assertFalse(EntitlementRules.isActive(Entitlement(false, null), now, now))
        assertFalse(EntitlementRules.isActive(null, now, now))
        assertFalse(EntitlementRules.isActive(Entitlement(true, null), 0L, now))
        assertFalse(EntitlementRules.isActive(Entitlement(true, null), now + day, now))
    }

    @Test fun serverIsoDatesParse() {
        assertEquals(1_764_583_200_000L, EntitlementRules.parseIso("2025-12-01T10:00:00.000Z"))
        assertNull(EntitlementRules.parseIso(null))
    }

    // ── route choice ──

    @Test fun plusWhenSignedInWithATier() = assertEquals(KeeperRoute.PLUS, KeeperRouting.choose(signedIn = true, plusActive = true))
    @Test fun noOwnKeyRouteWithoutPlus() = assertEquals(KeeperRoute.NONE, KeeperRouting.choose(signedIn = true, plusActive = false))
    @Test fun signedOutPlusIgnored() = assertEquals(KeeperRoute.NONE, KeeperRouting.choose(signedIn = false, plusActive = true))
    @Test fun nothingMeansNone() = assertEquals(KeeperRoute.NONE, KeeperRouting.choose(signedIn = false, plusActive = false))

    @Test fun freeTierRoutesToServer() {
        val free = Entitlement(true, null)
        val tier = EntitlementRules.serverTier("free", null, free, fetchedAtMs = 1_000L, nowMs = 2_000L)
        assertEquals("free", tier)
        assertEquals(KeeperRoute.PLUS, KeeperRouting.choose(signedIn = true, plusActive = tier != null))
    }
    @Test fun noServerTierUsesOwnKey() {
        assertEquals(null, EntitlementRules.serverTier(null, Entitlement(true, null), Entitlement(true, null), 1_000L, 2_000L))
        assertEquals(null, EntitlementRules.serverTier("free", null, Entitlement(false, null), 1_000L, 2_000L))
    }
    @Test fun upstreamBusyIsBusy() =
        assertEquals(KeeperRouting.Proxy.Fail(KeeperResult.Why.BUSY), KeeperRouting.mapProxy(503, """{"ok":false,"error":"upstream_busy"}"""))

    // ── account answers ──

    @Test fun notConfiguredReadsAsNotAvailable() =
        assertEquals(AccountProblem.NOT_AVAILABLE, AccountRules.problem(503, "accounts_not_configured"))

    @Test fun subscriptionIdIsNormalised() {
        assertEquals("I-ABC123DEF", AccountRules.subscriptionId("  i-abc123def "))
        assertNull(AccountRules.subscriptionId("FL2-XXXX"))
        // The Keeper screen also takes an Everywhere PC code; a phone code is not Keeper.
        assertEquals("FLP-ABCD-EFGH-IJKL", AccountRules.redeemable(" flp-abcd-efgh-ijkl "))
        assertEquals("I-ABC123DEF", AccountRules.redeemable("i-abc123def"))
        assertNull(AccountRules.redeemable("FL2-ABCD-EFGH-IJKL"))
        // Keeper Plus paid once: KP- and the payment id, however it was pasted.
        assertEquals("KP-5AB12345CD6789012", AccountRules.redeemable(" kp 5ab12345cd6789012 "))
        assertNull(AccountRules.redeemable("KP-123"))
        assertNull(AccountRules.redeemable("FLP-1"))
    }
}
