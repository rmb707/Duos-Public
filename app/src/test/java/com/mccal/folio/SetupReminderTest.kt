package com.mccal.folio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupReminderTest {
    private val day = 24 * 60 * 60 * 1000L
    private val start = 1_000_000_000_000L

    @Test fun `not on the day Folio was set up`() {
        assertFalse(SetupReminder.due(2, finished = false, firstSeen = start, snoozedUntil = 0, now = start + day / 2))
        assertTrue(SetupReminder.due(2, finished = false, firstSeen = start, snoozedUntil = 0, now = start + day))
    }

    @Test fun `Not Now waits until the snooze ends`() {
        val now = start + 2 * day
        assertFalse(SetupReminder.due(1, false, start, snoozedUntil = now + SetupReminder.NOT_NOW_MS, now = now))
        assertTrue(SetupReminder.due(1, false, start, snoozedUntil = now, now = now))
    }

    @Test fun `gone for good once setup was finished, even if a step comes undone`() {
        assertFalse(SetupReminder.due(1, finished = true, firstSeen = start, snoozedUntil = 0, now = start + 10 * day))
        assertFalse(SetupReminder.due(0, finished = false, firstSeen = start, snoozedUntil = 0, now = start + 10 * day))
    }

    @Test fun `never before the first time it was checked`() {
        assertFalse(SetupReminder.due(3, finished = false, firstSeen = 0, snoozedUntil = 0, now = start))
    }
}
