package com.focuslock.mdm

import android.widget.LinearLayout

/**
 * Side doors: the everyday apps that can also be a way around a promise. Every one is off
 * until you switch it on here, because for most people a gallery or the Play Store is just
 * daily life. Switching one on is always allowed; switching one off during a layer follows
 * the same rule as any loosening.
 */
class SideDoorsActivity : FocusScreenActivity() {

    override fun screenArt(): Int = R.drawable.art_door
    override fun screenTitle(): String = getString(R.string.side_doors_title)
    override fun screenSubtitle(): String = getString(R.string.side_doors_subtitle)

    override fun buildContent(column: LinearLayout) {
        column.addView(card { card ->
            listOf(
                SideDoors.Group.BROWSERS to (R.string.side_doors_browsers to R.string.side_doors_browsers_line),
                SideDoors.Group.GALLERY to (R.string.side_doors_gallery to R.string.side_doors_gallery_line),
                SideDoors.Group.GOOGLE to (R.string.side_doors_google to R.string.side_doors_google_line),
                SideDoors.Group.STORES to (R.string.side_doors_stores to R.string.side_doors_stores_line),
                SideDoors.Group.THEMES to (R.string.side_doors_themes to R.string.side_doors_themes_line),
                SideDoors.Group.PICTURE_NOTIFICATIONS to (R.string.side_doors_pictures to R.string.side_doors_pictures_line),
                SideDoors.Group.KEYBOARD_GIFS to (R.string.side_doors_gifs to R.string.side_doors_gifs_line),
            ).forEach { (group, text) ->
                card.addView(FocusUi.toggleRow(this, tokens, getString(text.first), getString(text.second), SideDoors.isOn(this, group)) { on ->
                    if (!on && !SessionLock.allows(this, EditDirection.LOOSEN)) FocusDialog.toast(this, SessionLock.refusalMessage(this))
                    else SideDoors.setOn(this, group, on)
                    refresh()
                })
            }
        })
        column.addView(FocusUi.spacer(this, 8))
        column.addView(FocusUi.caption(this, tokens, getString(R.string.side_doors_always)))
    }
}
