package com.vknxworks.proxctrl.data.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The settings a backup file holds: how the guest list looks, the warning thresholds, the two
 * privacy switches and the notification choices. Deliberately not in here, and so never exported or
 * imported: servers and their logins (token secrets, passwords, sessions), pinned certificates, which
 * addresses may use plain HTTP, the app lock and its PIN, whether background checks or push are on
 * (they depend on this phone's permissions and its ntfy registration), and Wake-on-LAN addresses.
 */
data class SettingsSnapshot(
    val guestSort: String,
    val guestSortDescending: Boolean,
    val guestCompact: Boolean,
    val diskWarnPercent: Int,
    val memWarnPercent: Int,
    val backupMaxAgeDays: Int,
    val hideScreenshots: Boolean,
    val hideRecents: Boolean,
    val notifyIntervalMinutes: Int,
    /** Names of the notification events that are switched off. */
    val notifyDisabled: Set<String>,
    val notifyCpuPercent: Int,
    val notifySustainMinutes: Int,
)

/** Exports the app's own settings to a JSON file and reads such a file back. */
object SettingsBackup {
    const val APP = "ProxCtrl"
    const val FORMAT = 1

    /** Bigger files than this aren't settings files; they're not read at all. */
    const val MAX_BYTES = 256 * 1024

    /** One setting an import would change, for the "this will be replaced" list. */
    enum class Field {
        GUEST_SORT, GUEST_SORT_DESCENDING, GUEST_COMPACT, DISK_WARN, MEM_WARN, BACKUP_AGE,
        HIDE_SCREENSHOTS, HIDE_RECENTS, NOTIFY_INTERVAL, NOTIFY_EVENTS, NOTIFY_CPU, NOTIFY_SUSTAIN,
    }

    class NotASettingsFile : IllegalArgumentException("not a ProxCtrl settings file")

    private val json = Json { prettyPrint = true }

    fun toJson(s: SettingsSnapshot, exportedAt: String, appVersion: String): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("app", APP)
            put("format", FORMAT)
            put("exported", exportedAt)
            put("appVersion", appVersion)
            put(
                "settings",
                buildJsonObject {
                    put("guestSort", s.guestSort)
                    put("guestSortDescending", s.guestSortDescending)
                    put("guestCompact", s.guestCompact)
                    put("diskWarnPercent", s.diskWarnPercent)
                    put("memWarnPercent", s.memWarnPercent)
                    put("backupMaxAgeDays", s.backupMaxAgeDays)
                    put("hideScreenshots", s.hideScreenshots)
                    put("hideRecents", s.hideRecents)
                },
            )
            put(
                "notifications",
                buildJsonObject {
                    put("intervalMinutes", s.notifyIntervalMinutes)
                    put("disabledEvents", buildJsonArray { s.notifyDisabled.sorted().forEach { add(JsonPrimitive(it)) } })
                    put("cpuPercent", s.notifyCpuPercent)
                    put("sustainMinutes", s.notifySustainMinutes)
                },
            )
        },
    )

    /**
     * Reads a settings file into what the settings would be after importing it over [current].
     * Anything missing, of the wrong type or out of range keeps its current value, and unknown names
     * are ignored, so a file from another version can't break anything. The two "Hide in…" switches
     * are only ever turned on by an import, never off. Only the settings above are read: whatever
     * else a file contains (a server, a certificate, a lock setting) is not looked at.
     *
     * [sorts], [events], [intervals], [cpuChoices] and [sustainChoices] are the values the app knows.
     */
    fun merge(
        text: String,
        current: SettingsSnapshot,
        sorts: Set<String>,
        events: Set<String>,
        intervals: Collection<Int>,
        cpuChoices: Collection<Int>,
        sustainChoices: Collection<Int>,
    ): SettingsSnapshot {
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: throw NotASettingsFile()
        if ((root["app"] as? JsonPrimitive)?.content != APP) throw NotASettingsFile()
        val format = (root["format"] as? JsonPrimitive)?.intOrNull ?: throw NotASettingsFile()
        if (format < 1 || format > FORMAT) throw NotASettingsFile()
        val s = root["settings"] as? JsonObject ?: JsonObject(emptyMap())
        val n = root["notifications"] as? JsonObject ?: JsonObject(emptyMap())

        fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        return current.copy(
            guestSort = s.text("guestSort")?.takeIf { it in sorts } ?: current.guestSort,
            guestSortDescending = s.bool("guestSortDescending") ?: current.guestSortDescending,
            guestCompact = s.bool("guestCompact") ?: current.guestCompact,
            diskWarnPercent = s.int("diskWarnPercent")?.takeIf { it in 50..99 } ?: current.diskWarnPercent,
            memWarnPercent = s.int("memWarnPercent")?.takeIf { it in 50..99 } ?: current.memWarnPercent,
            backupMaxAgeDays = s.int("backupMaxAgeDays")?.takeIf { it in 1..90 } ?: current.backupMaxAgeDays,
            hideScreenshots = current.hideScreenshots || s.bool("hideScreenshots") == true,
            hideRecents = current.hideRecents || s.bool("hideRecents") == true,
            notifyIntervalMinutes = n.int("intervalMinutes")?.takeIf { it in intervals } ?: current.notifyIntervalMinutes,
            notifyDisabled = (n["disabledEvents"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                ?.filter { it in events }?.toSet() ?: current.notifyDisabled,
            notifyCpuPercent = n.int("cpuPercent")?.takeIf { it in cpuChoices } ?: current.notifyCpuPercent,
            notifySustainMinutes = n.int("sustainMinutes")?.takeIf { it in sustainChoices } ?: current.notifySustainMinutes,
        )
    }

    /** Which settings differ between [current] and [incoming]. */
    fun changes(current: SettingsSnapshot, incoming: SettingsSnapshot): List<Field> = buildList {
        if (current.guestSort != incoming.guestSort) add(Field.GUEST_SORT)
        if (current.guestSortDescending != incoming.guestSortDescending) add(Field.GUEST_SORT_DESCENDING)
        if (current.guestCompact != incoming.guestCompact) add(Field.GUEST_COMPACT)
        if (current.diskWarnPercent != incoming.diskWarnPercent) add(Field.DISK_WARN)
        if (current.memWarnPercent != incoming.memWarnPercent) add(Field.MEM_WARN)
        if (current.backupMaxAgeDays != incoming.backupMaxAgeDays) add(Field.BACKUP_AGE)
        if (current.hideScreenshots != incoming.hideScreenshots) add(Field.HIDE_SCREENSHOTS)
        if (current.hideRecents != incoming.hideRecents) add(Field.HIDE_RECENTS)
        if (current.notifyIntervalMinutes != incoming.notifyIntervalMinutes) add(Field.NOTIFY_INTERVAL)
        if (current.notifyDisabled != incoming.notifyDisabled) add(Field.NOTIFY_EVENTS)
        if (current.notifyCpuPercent != incoming.notifyCpuPercent) add(Field.NOTIFY_CPU)
        if (current.notifySustainMinutes != incoming.notifySustainMinutes) add(Field.NOTIFY_SUSTAIN)
    }
}
