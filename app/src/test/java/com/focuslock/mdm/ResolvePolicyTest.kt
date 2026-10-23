package com.focuslock.mdm

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolvePolicyTest {

    private fun resolve(allowed: Boolean = false, explicit: String? = null, category: String? = null) =
        AppRules.resolvePolicy({ allowed }, { explicit }, { category })

    @Test fun alwaysAllowedBeatsEverything() =
        assertEquals(AppPolicy.ALLOW, resolve(allowed = true, explicit = "block", category = "hide"))

    @Test fun appsOwnSettingBeatsItsCategory() =
        assertEquals(AppPolicy.FRICTION, resolve(explicit = "friction", category = "block"))

    @Test fun categoryAppliesWhenTheAppSaidNothing() =
        assertEquals(AppPolicy.BLOCK, resolve(category = "block"))

    @Test fun nothingSetMeansOpenFreely() = assertEquals(AppPolicy.ALLOW, resolve())

    @Test fun lookupsStopAtTheFirstHit() {
        var asked = 0
        AppRules.resolvePolicy({ true }, { asked++; null }, { asked++; null })
        assertEquals(0, asked)
    }
}
