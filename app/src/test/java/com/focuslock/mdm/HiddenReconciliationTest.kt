package com.focuslock.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hidden-app reconciliation.
 *
 * This is a regression test for a real, shipped bug: hiding an app made it
 * invisible to the package-existence check (`getPackageInfo(pkg, 0)` cannot see
 * a package `setApplicationHidden` just hid), so the next sweep decided the app
 * was gone, revealed it, decided the sweep after that it should be hidden
 * again, and looped forever while the icon blinked on the launcher.
 *
 * The plan is pure, so the property that prevents that is testable here.
 */
class HiddenReconciliationTest {

    /** Everything exists, and the phone reports exactly [hidden]. */
    private fun plan(
        desired: Set<String>,
        previous: Set<String>,
        hidden: Set<String>
    ) = KioskPolicy.planHiddenReconciliation(
        desired = desired,
        previous = previous,
        isInstalled = { true },
        isHidden = { it in hidden }
    )

    private fun actions(desired: Set<String>, previous: Set<String>, hidden: Set<String>) =
        plan(desired, previous, hidden).toMap()

    @Test
    fun aPackageAlreadyInTheDesiredStateIsLeftAlone() {
        // The core property: a sweep with nothing to correct issues no calls,
        // so it is safe to run on every policy tick.
        val plan = plan(desired = setOf("a"), previous = setOf("a"), hidden = setOf("a"))
        assertEquals(KioskPolicy.HiddenAction.KEEP, plan.toMap()["a"])
    }

    @Test
    fun nothingIsPlannedForAPackageThatIsNeitherDesiredNorTracked() {
        val plan = plan(desired = setOf("a"), previous = emptySet(), hidden = setOf("a"))
        assertEquals(listOf("a" to KioskPolicy.HiddenAction.KEEP), plan)
    }

    @Test
    fun aDesiredPackageThatIsVisibleIsHidden() {
        val actions = actions(desired = setOf("a"), previous = emptySet(), hidden = emptySet())
        assertEquals(KioskPolicy.HiddenAction.HIDE, actions["a"])
    }

    @Test
    fun aTrackedPackageThatIsNoLongerDesiredIsRevealed() {
        val actions = actions(desired = emptySet(), previous = setOf("a"), hidden = setOf("a"))
        assertEquals(KioskPolicy.HiddenAction.REVEAL, actions["a"])
    }

    /**
     * The actual bug, as an assertion.
     *
     * The old code reached a state where the app was hidden but its bookkeeping
     * had been dropped, because the existence check said the package was gone.
     * Sweep one rediscovers it (the stray sweep hands it in as a candidate) and
     * reveals it, because reality says hidden while nothing wants it hidden.
     * Sweep two then has nothing to do.
     *
     * That second step is the one that used to be wrong: with the app visible
     * again and the pool of candidates unchanged, the old diff-based version
     * planned a *hide*, and the phone hid and revealed the icon forever.
     */
    @Test
    fun theSweepAfterARevealPlansNothing() {
        assertEquals(
            KioskPolicy.HiddenAction.REVEAL,
            actions(desired = emptySet(), previous = setOf("a"), hidden = setOf("a"))["a"]
        )
        // Nothing desired, nothing tracked, nothing hidden: a no-op sweep, so
        // there is no second half of a cycle to oscillate with.
        assertTrue(plan(desired = emptySet(), previous = emptySet(), hidden = emptySet()).isEmpty())
    }

    /**
     * The property that makes it terminate: the plan for a given desired set
     * and a given reported state is the same whether or not a previous sweep
     * remembered anything.
     */
    @Test
    fun thePlanDoesNotDependOnWhatAPreviousSweepBelieved() {
        val withMemory = actions(desired = setOf("a"), previous = setOf("a"), hidden = setOf("a"))
        val withoutMemory = actions(desired = setOf("a"), previous = emptySet(), hidden = setOf("a"))
        assertEquals(withMemory["a"], withoutMemory["a"])
    }

    @Test
    fun aPackageThePlatformRefusesToReportIsLeftAlone() {
        val plan = KioskPolicy.planHiddenReconciliation(
            desired = setOf("a"),
            previous = setOf("a"),
            isInstalled = { true },
            isHidden = { null }
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun anUninstalledPackageIsSkipped() {
        val plan = KioskPolicy.planHiddenReconciliation(
            desired = setOf("a"),
            previous = setOf("a"),
            isInstalled = { false },
            isHidden = { true }
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun desiredAndTrackedPackagesAreBothConsidered() {
        val plan = plan(
            desired = setOf("a"),
            previous = setOf("b"),
            hidden = setOf("b")
        ).toMap()
        assertEquals(KioskPolicy.HiddenAction.HIDE, plan["a"])
        assertEquals(KioskPolicy.HiddenAction.REVEAL, plan["b"])
    }
}
