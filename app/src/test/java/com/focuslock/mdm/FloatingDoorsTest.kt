package com.focuslock.mdm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingDoorsTest {
    @Test fun onlyASelectedGifOrStickerTabCounts() {
        assertTrue(FloatingDoors.isGifTab("GIFs", true))
        assertTrue(FloatingDoors.isGifTab(" Stickers ", true))
        assertFalse(FloatingDoors.isGifTab("GIFs", false))
        assertFalse(FloatingDoors.isGifTab("Emoji", true))
        assertFalse(FloatingDoors.isGifTab("Send a GIF to Ali", true))
        assertFalse(FloatingDoors.isGifTab(null, true))
    }
}
