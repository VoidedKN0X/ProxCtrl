package com.vknxworks.proxctrl.data.guests

import com.vknxworks.proxctrl.data.api.PveClient

/** What the rule editor holds. Empty text means "not set". */
data class RuleDraft(
    /** "in" or "out". */
    val type: String = "in",
    /** ACCEPT, DROP or REJECT. */
    val action: String = "ACCEPT",
    val enabled: Boolean = true,
    val macro: String = "",
    val proto: String = "",
    val source: String = "",
    val dest: String = "",
    val sport: String = "",
    val dport: String = "",
    val iface: String = "",
    val comment: String = "",
) {
    companion object {
        fun from(r: FirewallRule) = RuleDraft(
            type = r.type.ifBlank { "in" },
            action = r.action.ifBlank { "ACCEPT" },
            enabled = r.enabled,
            macro = r.macro.orEmpty(),
            proto = r.proto.orEmpty(),
            source = r.source.orEmpty(),
            dest = r.dest.orEmpty(),
            sport = r.sport.orEmpty(),
            dport = r.dport.orEmpty(),
            iface = r.iface.orEmpty(),
            comment = r.comment.orEmpty(),
        )
    }
}

/**
 * Editing a guest's firewall rules: POST / PUT / DELETE {base}/firewall/rules[/{pos}], which need
 * VM.Config.Network on /vms/{vmid} (pve-firewall, PVE::API2::Firewall::Rules). A new rule always goes
 * to the top (position 0), as in the web interface. Rules take effect at once when the firewall is on.
 */
object FirewallApi {
    const val PRIV = "VM.Config.Network"

    val TYPES = listOf("in", "out")
    val ACTIONS = listOf("ACCEPT", "DROP", "REJECT")

    /** Protocols that can carry an ICMP type. */
    private val ICMP = setOf("icmp", "icmpv6", "ipv6-icmp")

    /**
     * The form fields for [draft]. With [original] (an edit), settings that were set and are now empty
     * are listed under "delete". A macro brings its own protocol and ports, so those are dropped then.
     */
    fun ruleParams(draft: RuleDraft, original: FirewallRule?): Map<String, String> {
        val macro = draft.macro.trim()
        val usesMacro = macro.isNotEmpty()
        val proto = if (usesMacro) "" else draft.proto.trim()
        val hasPorts = !usesMacro && proto.isNotEmpty()
        val optional = linkedMapOf(
            "macro" to macro,
            "proto" to proto,
            "source" to draft.source.trim(),
            "dest" to draft.dest.trim(),
            "sport" to if (hasPorts) draft.sport.trim() else "",
            "dport" to if (hasPorts) draft.dport.trim() else "",
            "iface" to draft.iface.trim(),
            "comment" to draft.comment.trim(),
        )
        val had = original?.let {
            mapOf(
                "macro" to it.macro, "proto" to it.proto, "source" to it.source, "dest" to it.dest,
                "sport" to it.sport, "dport" to it.dport, "iface" to it.iface, "comment" to it.comment,
            )
        }.orEmpty()
        val delete = mutableListOf<String>()
        return buildMap {
            put("type", draft.type)
            put("action", draft.action)
            put("enable", if (draft.enabled) "1" else "0")
            for ((key, value) in optional) {
                if (value.isNotEmpty()) put(key, value) else if (!had[key].isNullOrEmpty()) delete += key
            }
            // An ICMP type only makes sense with an ICMP protocol; Proxmox refuses the rule otherwise.
            if (original?.icmpType != null && proto.lowercase() !in ICMP) delete += "icmp-type"
            if (delete.isNotEmpty()) put("delete", delete.joinToString(","))
            original?.digest?.takeIf { it.isNotBlank() }?.let { put("digest", it) }
        }
    }

    suspend fun create(client: PveClient, ref: GuestRef, draft: RuleDraft) {
        client.post("${ref.base}/firewall/rules", ruleParams(draft, null))
    }

    suspend fun update(client: PveClient, ref: GuestRef, original: FirewallRule, draft: RuleDraft) {
        client.put("${ref.base}/firewall/rules/${original.pos}", ruleParams(draft, original))
    }

    suspend fun setEnabled(client: PveClient, ref: GuestRef, rule: FirewallRule, enabled: Boolean) {
        client.put("${ref.base}/firewall/rules/${rule.pos}", withDigest(mapOf("enable" to if (enabled) "1" else "0"), rule))
    }

    /**
     * The "moveto" value that moves the rule at [pos] one place up or down. Proxmox puts the rule in
     * front of the rule that is at "moveto" now, so one place down is [pos] + 2.
     */
    fun moveTarget(pos: Int, up: Boolean): Int = if (up) pos - 1 else pos + 2

    suspend fun move(client: PveClient, ref: GuestRef, rule: FirewallRule, up: Boolean) {
        client.put("${ref.base}/firewall/rules/${rule.pos}", withDigest(mapOf("moveto" to moveTarget(rule.pos, up).toString()), rule))
    }

    suspend fun delete(client: PveClient, ref: GuestRef, rule: FirewallRule) {
        client.delete("${ref.base}/firewall/rules/${rule.pos}", withDigest(emptyMap(), rule))
    }

    private fun withDigest(params: Map<String, String>, rule: FirewallRule): Map<String, String> =
        rule.digest?.takeIf { it.isNotBlank() }?.let { params + ("digest" to it) } ?: params
}
