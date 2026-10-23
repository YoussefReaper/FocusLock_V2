package com.focuslock.mdm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Budgeted ladder.
 *
 * [EarnPool.budgetVerdict] is pure so the rule the whole Earn feature is can be
 * checked without a device, the same reason
 * [KioskPolicy.buildLockTaskPackages] is pure.
 *
 * The rule, stated once: a Budgeted app's daily minute limit is a free
 * allowance and runs out first; past it - or from the very first second, for an
 * app with no limit at all - the earned pool is what opens it, and an empty
 * pool shuts it.
 */
class EarnBudgetVerdictTest {

    private val pool = 40L * 60_000L

    @Test
    fun freeMinutesComeFirstWhenALimitIsSet() {
        assertEquals(
            EarnPool.BudgetVerdict.FREE_MINUTES,
            EarnPool.budgetVerdict(dailyLimitMinutes = 30, usedMinutesToday = 0, poolMs = 0L)
        )
        assertEquals(
            EarnPool.BudgetVerdict.FREE_MINUTES,
            EarnPool.budgetVerdict(dailyLimitMinutes = 30, usedMinutesToday = 29, poolMs = pool)
        )
    }

    @Test
    fun theLastFreeMinuteIsStillFree() {
        // 29 of 30 used: the 30th minute must still be free, not the first
        // minute the pool pays for. An off-by-one here silently charges the
        // person for time they were promised.
        assertEquals(
            EarnPool.BudgetVerdict.FREE_MINUTES,
            EarnPool.budgetVerdict(dailyLimitMinutes = 30, usedMinutesToday = 29, poolMs = 0L)
        )
    }

    @Test
    fun theMomentTheLimitIsReachedThePoolTakesOver() {
        assertEquals(
            EarnPool.BudgetVerdict.POOL,
            EarnPool.budgetVerdict(dailyLimitMinutes = 30, usedMinutesToday = 30, poolMs = pool)
        )
    }

    @Test
    fun aLimitWithAnEmptyPoolIsShut() {
        assertEquals(
            EarnPool.BudgetVerdict.BLOCKED,
            EarnPool.budgetVerdict(dailyLimitMinutes = 30, usedMinutesToday = 30, poolMs = 0L)
        )
    }

    @Test
    fun noLimitMeansThePoolFromTheFirstSecond() {
        assertEquals(
            EarnPool.BudgetVerdict.POOL,
            EarnPool.budgetVerdict(dailyLimitMinutes = null, usedMinutesToday = 0, poolMs = pool)
        )
        // ...and nothing to fall back on when it is empty. There are no free
        // minutes to spend, so the app is shut rather than given a default.
        assertEquals(
            EarnPool.BudgetVerdict.BLOCKED,
            EarnPool.budgetVerdict(dailyLimitMinutes = null, usedMinutesToday = 0, poolMs = 0L)
        )
    }

    @Test
    fun anOverrunLimitDoesNotFallBackToFreeMinutes() {
        // Someone can be past the limit - usage is read from the phone, so it
        // keeps counting whatever FocusLock was doing. It must not read as
        // "under the limit" again.
        assertEquals(
            EarnPool.BudgetVerdict.POOL,
            EarnPool.budgetVerdict(dailyLimitMinutes = 30, usedMinutesToday = 95, poolMs = pool)
        )
    }
}
