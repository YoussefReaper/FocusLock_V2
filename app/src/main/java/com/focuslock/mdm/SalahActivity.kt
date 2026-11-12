package com.focuslock.mdm

import android.location.Location
import android.location.LocationManager
import android.view.View
import android.widget.LinearLayout
import java.util.Calendar

/**
 * Salah settings: where you pray, how the times are worked out, and how long
 * the phone stays quiet. Today's times are shown with a small nudge each.
 */
class SalahActivity : FocusScreenActivity() {

    override fun screenArt(): Int = R.drawable.art_salah
    override fun screenTitle(): String = getString(R.string.salah_title)
    override fun screenSubtitle(): String = getString(R.string.salah_subtitle)

    private fun guarded(direction: EditDirection, change: () -> Unit) {
        if (!SessionLock.allows(this, direction)) {
            FocusDialog.toast(this, SessionLock.refusalMessage(this)); return
        }
        change(); AppBlockerService.startIfNeeded(this); refresh()
    }

    override fun buildContent(column: LinearLayout) {
        column.addView(card { card ->
            card.addView(FocusUi.toggleRow(this, tokens, getString(R.string.salah_title),
                getString(R.string.salah_toggle_subtitle), Salah.isOn(this)) { on ->
                guarded(if (on) EditDirection.TIGHTEN else EditDirection.LOOSEN) { Salah.setOn(this, on) }
            })
        })

        column.addView(sectionLabel(getString(R.string.salah_section_place)))
        column.addView(card { card ->
            card.addView(FocusUi.rowTitle(this, tokens, Salah.placeName(this)))
            card.addView(FocusUi.caption(this, tokens, "%.3f, %.3f · %s".format(Salah.lat(this), Salah.lng(this), Salah.zone(this).id)))
            card.addView(FocusUi.spacer(this, 10))
            card.addView(FocusUi.tonalButton(this, tokens, getString(R.string.salah_use_location)) { useLocation() })
        })

        column.addView(sectionLabel(getString(R.string.salah_section_method)))
        column.addView(card { card ->
            val methods = Salah.Method.values().toList()
            val labels = listOf(R.string.salah_m_egypt, R.string.salah_m_mwl, R.string.salah_m_umm, R.string.salah_m_isna, R.string.salah_m_karachi).map { getString(it) }
            card.addView(FocusUi.horizontalScroll(this, FocusUi.chipStrip(this, tokens, labels, methods.indexOf(Salah.method(this))) { i ->
                guarded(EditDirection.TIGHTEN) { Salah.setMethod(this, methods[i]) }
            }))
            card.addView(FocusUi.spacer(this, 10))
            card.addView(FocusUi.caption(this, tokens, getString(R.string.salah_asr_label)))
            card.addView(FocusUi.chipStrip(this, tokens, listOf(getString(R.string.salah_asr_standard), getString(R.string.salah_asr_hanafi)),
                if (Salah.hanafi(this)) 1 else 0) { i -> guarded(EditDirection.TIGHTEN) { Salah.setHanafi(this, i == 1) } })
        })

        column.addView(sectionLabel(getString(R.string.salah_section_window)))
        column.addView(card { card ->
            val options = listOf(15, 20, 30, 45)
            val current = Salah.windowMinutes(this)
            card.addView(FocusUi.chipStrip(this, tokens, options.map { getString(R.string.salah_minutes, it) }, options.indexOf(current)) { i ->
                guarded(if (options[i] >= current) EditDirection.TIGHTEN else EditDirection.LOOSEN) { Salah.setWindowMinutes(this, options[i]) }
            })
            card.addView(FocusUi.spacer(this, 8))
            card.addView(FocusUi.caption(this, tokens, getString(R.string.salah_window_note, Salah.BEFORE_MIN, Salah.JUMUAH_MIN, Salah.PRAYED_MIN)))
        })

        column.addView(sectionLabel(getString(R.string.salah_section_today)))
        column.addView(card { card -> Salah.times(this, Calendar.getInstance(Salah.zone(this))).forEach { (name, at) -> card.addView(timeRow(name, at)) } })
    }

    private fun timeRow(name: String, at: Long): View {
        val row = FocusUi.row(this)
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val label = FocusUi.body(this, tokens, Salah.prayerName(this, name) + "   " +
            TimeText.ofDay(this, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)))
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val off = Salah.offset(this, name)
        if (off != 0) row.addView(FocusUi.caption(this, tokens, (if (off > 0) "+" else "") + off + "  "))
        row.addView(FocusUi.chip(this, tokens, "−", false) { guarded(EditDirection.TIGHTEN) { Salah.setOffset(this, name, off - 1) } })
        row.addView(FocusUi.chip(this, tokens, "+", false) { guarded(EditDirection.TIGHTEN) { Salah.setOffset(this, name, off + 1) } })
        row.setPadding(0, FocusUi.dp(this, 6), 0, FocusUi.dp(this, 6))
        return row
    }

    private fun useLocation() {
        val granted = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION); return
        }
        val lm = getSystemService(LocationManager::class.java)
        val loc: Location? = try {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (_: SecurityException) { null }
        if (loc == null) { FocusDialog.toast(this, getString(R.string.salah_no_location)); return }
        guarded(EditDirection.TIGHTEN) {
            Salah.setPlace(this, loc.latitude, loc.longitude, getString(R.string.salah_here))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) useLocation()
    }

    private companion object { const val REQUEST_LOCATION = 41 }
}
