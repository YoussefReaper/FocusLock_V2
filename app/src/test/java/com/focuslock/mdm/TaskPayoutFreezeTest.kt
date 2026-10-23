package com.focuslock.mdm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The task payout freeze.
 *
 * Regression test for a real exploit, reported from use: start an Earn session,
 * open the running task, switch its verification to "I say so", set the reward
 * to 120 minutes, tap done once. The pool went up by 120 for no work.
 *
 * Two independent things have to hold for that to be impossible:
 *
 *  1. the payout is computed from the deal frozen at session start, not from
 *     the task as it stands now - see [EarnSession.EarnDeal]; and
 *  2. an edit that would make a task pay more waits for the session to end -
 *     [FocusTaskStore.loosensPayout], checked here.
 *
 * Either alone would have stopped the reported exploit; both together mean the
 * hole is not merely closed but absent.
 */
class TaskPayoutFreezeTest {

    private fun task() = FocusTaskStore.newTask("Essay")

    @Test
    fun weakeningVerificationLoosens() {
        val before = task().copy(verification = Verification.TIMER)
        assertTrue(FocusTaskStore.loosensPayout(before, before.copy(verification = Verification.MANUAL)))
    }

    @Test
    fun strengtheningVerificationDoesNotLoosen() {
        val before = task().copy(verification = Verification.MANUAL)
        assertFalse(FocusTaskStore.loosensPayout(before, before.copy(verification = Verification.TIMER)))
        // Photo is the strongest proof, so it must also be allowed upward.
        assertFalse(FocusTaskStore.loosensPayout(before, before.copy(verification = Verification.PHOTO)))
    }

    @Test
    fun raisingTheRewardLoosens() {
        val before = task().copy(rewardMin = 10)
        assertTrue(FocusTaskStore.loosensPayout(before, before.copy(rewardMin = 120)))
    }

    @Test
    fun loweringTheRewardDoesNotLoosen() {
        val before = task().copy(rewardMin = 120)
        assertFalse(FocusTaskStore.loosensPayout(before, before.copy(rewardMin = 10)))
        assertFalse(FocusTaskStore.loosensPayout(before, before.copy(rewardMin = 120)))
    }

    @Test
    fun pinningARewardWhereThereWasNoneLoosens() {
        // No explicit reward means the user's rate decides, which cannot be more
        // than the rate-derived amount; pinning one can only add.
        val before = task().copy(rewardMin = null)
        assertTrue(FocusTaskStore.loosensPayout(before, before.copy(rewardMin = 120)))
    }

    @Test
    fun markingATaskEnjoyableDoesNotLoosen() {
        // Enjoyable pays nothing, so switching it on can only take away.
        val before = task().copy(enjoyable = false)
        assertFalse(FocusTaskStore.loosensPayout(before, before.copy(enjoyable = true)))
    }

    @Test
    fun unmarkingEnjoyableLoosens() {
        val before = task().copy(enjoyable = true)
        assertTrue(FocusTaskStore.loosensPayout(before, before.copy(enjoyable = false)))
    }

    @Test
    fun ordinaryEditsNeverLoosen() {
        // The freeze must not get in the way of using the task list: none of
        // these touch what the task pays.
        val before = task()
        assertFalse(FocusTaskStore.loosensPayout(before, before.copy(title = "Something else")))
        assertFalse(FocusTaskStore.loosensPayout(before, before.copy(notes = "a note")))
        assertFalse(FocusTaskStore.loosensPayout(before, before.copy(completed = true)))
    }

    @Test
    fun aBrandNewTaskHasNoPayoutToLoosen() {
        assertFalse(FocusTaskStore.loosensPayout(null, task().copy(rewardMin = 500)))
    }
}