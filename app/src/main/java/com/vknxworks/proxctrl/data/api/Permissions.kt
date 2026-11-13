package com.vknxworks.proxctrl.data.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Effective privileges of the signed-in user or token, from GET /access/permissions:
 * ACL path → privilege → whether it propagates to paths below.
 *
 * Paths without their own entry inherit the propagating privileges of their nearest listed parent,
 * which is how Proxmox resolves them. Used to grey out actions the token can't perform instead of
 * letting them fail.
 */
class Permissions(private val byPath: Map<String, Map<String, Boolean>>) {

    /** True when [priv] is granted on [path] (e.g. "/vms/105", "VM.PowerMgmt"). */
    fun has(path: String, priv: String): Boolean {
        val normalized = normalize(path)
        byPath[normalized]?.let { return it.containsKey(priv) }
        var parent = parentOf(normalized)
        while (parent != null) {
            byPath[parent]?.let { privs -> return privs[priv] == true }
            parent = parentOf(parent)
        }
        return false
    }

    /** True when [priv] is granted on [path] or anywhere below it (e.g. VM.Audit on some guest). */
    fun hasAnywhere(path: String, priv: String): Boolean {
        val prefix = normalize(path)
        if (has(prefix, priv)) return true
        val below = if (prefix == "/") "/" else "$prefix/"
        return byPath.any { (p, privs) -> p.startsWith(below) && privs.containsKey(priv) }
    }

    /** True when nothing at all can be changed (a read-only token). */
    val isReadOnly: Boolean
        get() = byPath.values.none { privs -> privs.keys.any { it !in READ_ONLY_PRIVS } }

    val isEmpty: Boolean get() = byPath.isEmpty()

    companion object {
        val NONE = Permissions(emptyMap())

        /** Privileges that only let you look. Anything else counts as a write privilege. */
        private val READ_ONLY_PRIVS = setOf(
            "VM.Audit", "VM.Monitor", "VM.GuestAgent.Audit", "Sys.Audit", "Sys.Syslog", "Datastore.Audit",
            "SDN.Audit", "Pool.Audit", "Mapping.Audit", "Permissions.Audit", "Realm.Audit", "Group.Audit", "User.Audit",
        )

        fun parse(data: JsonElement?): Permissions {
            val obj = data as? JsonObject ?: return NONE
            val map = obj.mapNotNull { (path, privs) ->
                val p = privs as? JsonObject ?: return@mapNotNull null
                normalize(path) to p.mapValues { (_, v) -> (runCatching { v.jsonPrimitive.intOrNull }.getOrNull() ?: 0) != 0 }
            }.toMap()
            return Permissions(map)
        }

        private fun normalize(path: String): String {
            val trimmed = "/" + path.trim().trim('/')
            return if (trimmed == "/") "/" else trimmed
        }

        private fun parentOf(path: String): String? = when (path) {
            "/" -> null
            else -> path.substringBeforeLast('/').ifEmpty { "/" }
        }
    }
}
