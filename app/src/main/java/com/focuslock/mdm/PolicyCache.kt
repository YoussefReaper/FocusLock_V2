package com.focuslock.mdm

import android.os.SystemClock

/**
 * A memo for the enforcement loop.
 *
 * [RuleEngine] runs several times a second, and a naive implementation would
 * re-parse every JSON blob on every tick just to answer "is this app allowed".
 * Each entry here is tagged with the [PolicySync] revision it was computed
 * under, so a cached answer is thrown away the instant any store is written and
 * a toggle still takes effect immediately.
 *
 * Entries also age out after [MAX_AGE_MS]. A few of them depend on something
 * besides the stores - which apps are installed, for a blocked category - and
 * those used to be refreshed as a side effect of every FocusLock resume
 * bumping the revision. Resumes no longer do that (see [PolicySync.reapply]),
 * so the age limit is what keeps a newly installed app in a blocked category
 * from going unblocked until the next settings change.
 */
object PolicyCache {

    private const val MAX_AGE_MS = 60_000L

    private class Entry(val revision: Long, val at: Long, val value: Any?)

    private val entries = HashMap<String, Entry>()

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun <T> get(key: String, compute: () -> T): T {
        val revision = PolicySync.revision()
        val now = SystemClock.elapsedRealtime()
        val cached = entries[key]
        if (cached != null && cached.revision == revision && now - cached.at < MAX_AGE_MS) {
            return cached.value as T
        }
        val value = compute()
        entries[key] = Entry(revision, now, value)
        return value
    }
}
