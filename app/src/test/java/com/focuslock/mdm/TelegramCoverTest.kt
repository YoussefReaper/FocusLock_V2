package com.focuslock.mdm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramCoverTest {
    @Test fun onlyMediaLabelsAreCovered() {
        assertTrue(TelegramCover.isMedia("Photo"))
        assertTrue(TelegramCover.isMedia("Video, 0:42"))
        assertTrue(TelegramCover.isMedia("GIF"))
        assertTrue(TelegramCover.isMedia("صورة"))
        assertFalse(TelegramCover.isMedia("Photography tips for beginners and a long message about cameras and lenses"))
        assertFalse(TelegramCover.isMedia("Photosynthesis"))
        assertFalse(TelegramCover.isMedia(null))
    }
}
