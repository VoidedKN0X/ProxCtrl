package com.vknxworks.proxctrl.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The settings file: what it holds, and what an import will and won't take from one. */
class SettingsBackupTest {

    private val base = SettingsSnapshot(
        guestSort = "ID", guestSortDescending = false, guestCompact = false, diskWarnPercent = 80, memWarnPercent = 85, backupMaxAgeDays = 7,
        hideScreenshots = false, hideRecents = false, notifyIntervalMinutes = 15, notifyDisabled = emptySet(), notifyCpuPercent = 90, notifySustainMinutes = 10,
    )
    private val sorts = setOf("ID", "NAME", "CPU")
    private val events = setOf("GUEST_DISK", "UPDATES", "NODE_CPU")

    private fun merge(text: String, current: SettingsSnapshot = base) =
        SettingsBackup.merge(text, current, sorts, events, listOf(15, 30, 60), listOf(70, 80, 90, 95), listOf(5, 10, 15, 30))

    @Test
    fun `an export reads back as the same settings`() {
        val other = base.copy(
            guestSort = "NAME", guestSortDescending = true, guestCompact = true, diskWarnPercent = 90, memWarnPercent = 95, backupMaxAgeDays = 3,
            hideScreenshots = true, hideRecents = true, notifyIntervalMinutes = 60, notifyDisabled = setOf("UPDATES", "NODE_CPU"), notifyCpuPercent = 80, notifySustainMinutes = 30,
        )
        val text = SettingsBackup.toJson(other, "2026-11-21T20:00:00Z", "1.9.0")
        assertEquals(other, merge(text))
        assertEquals(SettingsBackup.Field.entries.toList(), SettingsBackup.changes(base, merge(text)))
        assertTrue(SettingsBackup.changes(other, merge(text, other)).isEmpty())
    }

    @Test
    fun `the file holds no secrets or server details`() {
        val text = SettingsBackup.toJson(base, "now", "1.9.0").lowercase()
        listOf("token", "password", "secret", "pin", "ticket", "cert", "http", "server", "lock").forEach { assertFalse(it, text.contains(it)) }
    }

    @Test
    fun `other files are refused`() {
        listOf("", "not json", "[]", """{"app":"TrueCtrl","format":1}""", """{"app":"ProxCtrl"}""", """{"app":"ProxCtrl","format":99}""").forEach {
            try {
                merge(it)
                fail("accepted: $it")
            } catch (e: SettingsBackup.NotASettingsFile) {
                // expected
            }
        }
    }

    @Test
    fun `bad or unknown values keep what the phone has`() {
        val text = """{"app":"ProxCtrl","format":1,
            "settings":{"guestSort":"WEIGHT","diskWarnPercent":5,"memWarnPercent":"90","backupMaxAgeDays":1000,"guestCompact":"yes","lockChanges":false,"certPin":"AA:BB"},
            "notifications":{"intervalMinutes":1,"disabledEvents":["UPDATES","MADE_UP",7],"cpuPercent":12,"push":true,"polling":true},
            "servers":[{"url":"http://evil.example","certPin":"00"}]}"""
        val merged = merge(text)
        assertEquals(base.copy(notifyDisabled = setOf("UPDATES")), merged)
        assertEquals(listOf(SettingsBackup.Field.NOTIFY_EVENTS), SettingsBackup.changes(base, merged))
    }

    @Test
    fun `an import can turn the hide switches on but never off`() {
        val hidden = base.copy(hideScreenshots = true, hideRecents = true)
        val off = SettingsBackup.toJson(base, "now", "1.9.0")
        assertEquals(hidden, merge(off, hidden))
        val on = SettingsBackup.toJson(hidden, "now", "1.9.0")
        assertTrue(merge(on).hideScreenshots)
        assertTrue(merge(on).hideRecents)
    }
}
