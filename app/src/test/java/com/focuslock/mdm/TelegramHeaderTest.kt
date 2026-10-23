package com.focuslock.mdm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading a chat's name from the official Telegram app's header.
 *
 * Telegram gives its views no resource ids at all, so the header's description
 * - "Title\nsubtitle" - is the only reliable place the chat's name appears.
 * These are the shapes seen on a real phone.
 */
class TelegramHeaderTest {

    private val wide = 800
    private val min = 400

    @Test
    fun aChannelHeaderGivesItsName() {
        assertEquals(
            "Lumosel Remastred",
            TelegramGuard.titleFromHeaderDescription("Lumosel Remastred\n6.6K subscribers", wide, true, min)
        )
    }

    @Test
    fun aPersonAndAGroupGiveTheirNames() {
        assertEquals("Mum", TelegramGuard.titleFromHeaderDescription("Mum\nlast seen recently", wide, true, min))
        assertEquals("Senior 27", TelegramGuard.titleFromHeaderDescription("Senior 27\n412 members", wide, true, min))
    }

    @Test
    fun aSingleLineIsNotAHeader() {
        // "Reply, Name, message text" - a message bubble, not the header.
        assertNull(TelegramGuard.titleFromHeaderDescription("Reply, Lumosel Remastred, Migration", wide, true, min))
        assertNull(TelegramGuard.titleFromHeaderDescription("Telegram", wide, true, min))
    }

    @Test
    fun outsideTheHeaderOrTooNarrowIsIgnored() {
        assertNull(TelegramGuard.titleFromHeaderDescription("Name\nonline", wide, false, min))
        assertNull(TelegramGuard.titleFromHeaderDescription("Name\nonline", 200, true, min))
    }

    @Test
    fun anEmptyFirstLineIsNotATitle() {
        assertNull(TelegramGuard.titleFromHeaderDescription("\nonline", wide, true, min))
        assertNull(TelegramGuard.titleFromHeaderDescription("   \nonline", wide, true, min))
        assertNull(TelegramGuard.titleFromHeaderDescription(null, wide, true, min))
    }
}
