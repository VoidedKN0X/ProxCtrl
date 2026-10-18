package com.vknxworks.proxctrl.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Proxmox responses are parsed leniently: unknown fields are ignored and every field has a default,
// so a field missing on some version never crashes the app.

/** GET /version */
@Serializable
data class PveVersion(
    val version: String = "",
    val release: String = "",
    val repoid: String = "",
) {
    /** Major version, e.g. 9 for 9.2.18; 0 when unknown. */
    val major: Int get() = version.substringBefore('.').toIntOrNull() ?: 0
}

/** GET /access/domains: a login realm. */
@Serializable
data class Realm(
    val realm: String = "",
    val type: String = "",
    val comment: String = "",
    /** Realm-wide TFA type, if the realm enforces one. */
    val tfa: String = "",
    @SerialName("default") val isDefault: Int = 0,
) {
    val label: String get() = comment.ifBlank { realm }
}

/** POST /access/ticket */
@Serializable
data class TicketResponse(
    val username: String = "",
    val ticket: String = "",
    @SerialName("CSRFPreventionToken") val csrf: String = "",
    @SerialName("NeedTFA") val needTfa: Int = 0,
    val clustername: String = "",
)

/** GET /nodes: a node in the cluster (or the only node). */
@Serializable
data class NodeEntry(
    val node: String = "",
    val status: String = "",
)

/** GET /cluster/status: entries are either the cluster itself or its nodes. */
@Serializable
data class ClusterStatusEntry(
    val type: String = "",
    val name: String = "",
    val nodes: Int = 0,
    val quorate: Int = 0,
)
