package com.focuslock.mdm

import android.view.View
import android.widget.LinearLayout

/**
 * Places and networks.
 *
 * "Social off at school, maps always on" is the classic version of this, and it
 * works because context does most of the work that willpower otherwise has to.
 *
 * Two deliberate limits: location is only ever read from the last known fix, so
 * FocusLock never runs a location stream, and a place whose condition cannot be
 * determined blocks nothing. Uncertainty must never turn into a lockout.
 */
class PlaceRulesActivity : FocusScreenActivity() {

    override fun screenArt(): Int = R.drawable.art_rules

    override fun screenTitle(): String = getString(R.string.place_rules_title)

    override fun screenSubtitle(): String = getString(R.string.place_rules_subtitle)

    override fun buildContent(column: LinearLayout) {
        column.addView(buildToggles())
        column.addView(sectionLabel(getString(R.string.place_rules_section_saved)))
        column.addView(buildPlaceList())
        column.addView(sectionLabel(getString(R.string.place_rules_section_right_now)))
        column.addView(buildStatusCard())
    }

    private fun buildToggles(): View = card { card ->
        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.place_rules_location_title),
                getString(R.string.place_rules_location_subtitle),
                CapabilityRegistry.isEnabled(this, Capabilities.LOCATION_BLOCK)
            ) { value ->
                if (!CapabilityRegistry.setEnabled(this, Capabilities.LOCATION_BLOCK, value)) {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                } else if (value && !PlaceRules.hasLocationPermission(this)) {
                    requestLocation()
                }
                refresh()
            }
        )
        card.addView(FocusUi.divider(this, tokens))
        card.addView(
            FocusUi.toggleRow(
                this,
                tokens,
                getString(R.string.place_rules_network_title),
                getString(R.string.place_rules_network_subtitle),
                CapabilityRegistry.isEnabled(this, Capabilities.WIFI_CONDITIONS)
            ) { value ->
                if (!CapabilityRegistry.setEnabled(this, Capabilities.WIFI_CONDITIONS, value)) {
                    FocusDialog.toast(this, SessionLock.refusalMessage(this))
                } else if (value && !PlaceRules.hasLocationPermission(this)) {
                    // Android 10+ requires location permission to read the connected Wi-Fi SSID.
                    requestLocation()
                }
                refresh()
            }
        )

        if ((CapabilityRegistry.isEnabled(this, Capabilities.LOCATION_BLOCK) ||
                CapabilityRegistry.isEnabled(this, Capabilities.WIFI_CONDITIONS)) &&
            !PlaceRules.hasLocationPermission(this)
        ) {
            card.addView(FocusUi.spacer(this, 10))
            card.addView(
                FocusUi.primaryButton(this, tokens, getString(R.string.place_rules_grant_location)) { requestLocation() }
            )
        }
    }

    private fun requestLocation() {
        requestPermissions(
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            REQUEST_LOCATION
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) refresh()
    }

    private fun buildPlaceList(): View = card { card ->
        val places = PlaceRules.all(this)
        val active = PlaceRules.activePlaces(this).map { it.id }.toSet()

        if (places.isEmpty()) {
            card.addView(FocusUi.emptyState(this, tokens, getString(R.string.place_rules_empty)))
        } else {
            places.forEachIndexed { index, place ->
                card.addView(
                    FocusUi.listRow(
                        this,
                        tokens,
                        place.label,
                        describePlace(place),
                        trailing = if (place.id in active) {
                            FocusUi.pill(this, tokens, getString(R.string.place_rules_active), tokens.accent)
                        } else {
                            FocusUi.chevron(this, tokens)
                        }
                    ) { editPlace(place) }
                )
                if (index < places.size - 1) card.addView(FocusUi.divider(this, tokens))
            }
        }

        card.addView(FocusUi.spacer(this, 12))
        card.addView(FocusUi.primaryButton(this, tokens, getString(R.string.place_rules_add_here)) { addHere() })
        card.addView(FocusUi.spacer(this, 8))
        card.addView(FocusUi.secondaryButton(this, tokens, getString(R.string.place_rules_add_wifi)) { addWifi() })
    }

    private fun describePlace(place: Place): String {
        val where = when {
            place.hasWifi && place.hasCoordinates ->
                getString(R.string.place_rules_where_wifi_and_radius, place.wifiSsid, place.radiusMeters)
            place.hasWifi -> getString(R.string.place_rules_where_wifi, place.wifiSsid)
            place.hasCoordinates -> getString(R.string.place_rules_where_radius, place.radiusMeters)
            else -> getString(R.string.place_rules_where_nothing)
        }
        val what = if (place.blockedCategories.isEmpty() && place.blockedPackages.isEmpty()) {
            getString(R.string.place_rules_what_nothing_blocked)
        } else {
            getString(
                R.string.place_rules_what_categories_apps,
                place.blockedCategories.size,
                place.blockedPackages.size
            )
        }
        val trigger = if (place.trigger == PlaceTrigger.INSIDE) {
            getString(R.string.place_rules_trigger_here)
        } else {
            getString(R.string.place_rules_trigger_away)
        }
        return getString(R.string.place_rules_describe_place, where, what, trigger)
    }

    private fun addHere() {
        // A new place rule only ever blocks more. PlaceRules.save judges it.
        if (!PlaceRules.hasLocationPermission(this)) {
            requestLocation()
            return
        }
        val cached = PlaceRules.lastKnownLocation(this)
        if (cached != null) {
            promptPlaceAtLocation(cached)
            return
        }
        FocusDialog.toast(this, getString(R.string.place_rules_locating_toast))
        PlaceRules.requestFreshLocation(this) { fresh ->
            if (isFinishing || isDestroyed) return@requestFreshLocation
            if (fresh == null) {
                FocusDialog.info(
                    this,
                    getString(R.string.place_rules_no_recent_location_title),
                    getString(R.string.place_rules_no_recent_location_message)
                )
            } else {
                refresh()
                promptPlaceAtLocation(fresh)
            }
        }
    }

    private fun promptPlaceAtLocation(here: android.location.Location) {
        FocusDialog.textInput(
            this,
            title = getString(R.string.place_rules_name_place_title),
            subtitle = getString(R.string.place_rules_name_place_subtitle),
            hint = getString(R.string.common_name_hint)
        ) { name ->
            if (name.isBlank()) return@textInput
            val place = PlaceRules.newPlace(
                label = name,
                latitude = here.latitude,
                longitude = here.longitude,
                wifiSsid = PlaceRules.currentWifiSsid(this).orEmpty()
            )
            PlaceRules.add(this, place)
            refresh()
            editPlace(place)
        }
    }

    private fun addWifi() {
        // Android 10+ hides the Wi-Fi SSID ("<unknown ssid>") unless location permission is granted.
        if (!PlaceRules.hasLocationPermission(this)) {
            requestLocation()
            return
        }
        // A new network rule only ever blocks more. PlaceRules.save judges it.
        val ssid = PlaceRules.currentWifiSsid(this)
        if (ssid == null) {
            FocusDialog.info(
                this,
                getString(R.string.place_rules_not_on_wifi_title),
                getString(R.string.place_rules_not_on_wifi_message)
            )
            return
        }
        FocusDialog.textInput(
            this,
            title = getString(R.string.place_rules_name_network_title),
            subtitle = getString(R.string.place_rules_connected_to, ssid),
            hint = getString(R.string.common_name_hint),
            value = ssid
        ) { name ->
            if (name.isBlank()) return@textInput
            val place = PlaceRules.newPlace(label = name, wifiSsid = ssid)
            PlaceRules.add(this, place)
            refresh()
            editPlace(place)
        }
    }

    private fun editPlace(place: Place) {
        // Opening the editor is free; PlaceRules.save refuses an edit that loosens.
        var working = place

        FocusDialog.custom(
            this,
            title = place.label,
            subtitle = describePlace(place),
            confirmLabel = getString(R.string.common_save),
            cancelLabel = getString(R.string.common_cancel),
            onConfirm = {
                PlaceRules.update(this, working)
                refresh()
            }
        ) { body, dialogTokens, refreshBody ->
            body.addView(FocusUi.caption(this, dialogTokens, getString(R.string.place_rules_caption_when)))
            PlaceTrigger.values().forEach { trigger ->
                val marker = FocusUi.pill(
                    this,
                    dialogTokens,
                    if (trigger == working.trigger) getString(R.string.common_now) else getString(R.string.common_set),
                    if (trigger == working.trigger) dialogTokens.accent else dialogTokens.textMuted
                )
                body.addView(
                    FocusUi.listRow(this, dialogTokens, trigger.label(this), null, trailing = marker) {
                        working = working.copy(trigger = trigger)
                        PlaceRules.update(this, working)
                        refreshBody()
                    }
                )
            }

            body.addView(FocusUi.divider(this, dialogTokens, 8))
            body.addView(FocusUi.caption(this, dialogTokens, getString(R.string.place_rules_caption_what_blocks)))
            AppCategory.ruleTargets.forEach { category ->
                body.addView(
                    FocusUi.toggleRow(
                        this,
                        dialogTokens,
                        category.label(this),
                        null,
                        category in working.blockedCategories
                    ) { checked ->
                        val next = if (checked) {
                            working.blockedCategories + category
                        } else {
                            working.blockedCategories - category
                        }
                        working = working.copy(blockedCategories = next)
                        PlaceRules.update(this, working)
                    }
                )
            }

            body.addView(
                FocusUi.listRow(
                    this,
                    dialogTokens,
                    getString(R.string.place_rules_specific_apps),
                    getString(R.string.common_chosen_count, working.blockedPackages.size),
                    trailing = FocusUi.chevron(this, dialogTokens)
                ) {
                    pickApps(
                        title = getString(R.string.place_rules_block_at_title, working.label),
                        subtitle = null,
                        selected = working.blockedPackages
                    ) { selected ->
                        working = working.copy(blockedPackages = selected)
                        PlaceRules.update(this, working)
                        refreshBody()
                    }
                }
            )

            if (working.hasCoordinates) {
                body.addView(
                    FocusUi.sliderRow(
                        this,
                        dialogTokens,
                        getString(R.string.place_rules_radius_label),
                        50,
                        1_000,
                        working.radiusMeters,
                        { getString(R.string.common_meters_suffix, it) }
                    ) { value ->
                        working = working.copy(radiusMeters = value)
                    }
                )
            }

            body.addView(FocusUi.divider(this, dialogTokens, 8))
            body.addView(
                FocusUi.dangerButton(this, dialogTokens, getString(R.string.place_rules_delete_place)) {
                    PlaceRules.remove(this, working.id)
                    refresh()
                }
            )
        }
    }

    private fun buildStatusCard(): View = card { card ->
        val ssid = PlaceRules.currentWifiSsid(this)
        card.addView(
            FocusUi.listRow(
                this,
                tokens,
                getString(R.string.place_rules_status_wifi_title),
                ssid ?: getString(R.string.place_rules_not_connected_wifi)
            )
        )
        val here = PlaceRules.lastKnownLocation(this)
        card.addView(
            FocusUi.listRow(
                this,
                tokens,
                getString(R.string.place_rules_status_location_title),
                when {
                    !PlaceRules.hasLocationPermission(this) -> getString(R.string.place_rules_permission_not_granted)
                    here == null -> getString(R.string.place_rules_no_recent_fix)
                    else -> getString(R.string.place_rules_known_accuracy, here.accuracy.toInt())
                }
            )
        )
        val active = PlaceRules.activePlaces(this)
        card.addView(
            FocusUi.listRow(
                this,
                tokens,
                getString(R.string.place_rules_matching_places_title),
                if (active.isEmpty()) getString(R.string.common_none_right_now) else active.joinToString { it.label }
            )
        )
    }

    companion object {
        private const val REQUEST_LOCATION = 4712
    }
}
