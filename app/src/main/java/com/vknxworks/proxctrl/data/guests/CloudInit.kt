package com.vknxworks.proxctrl.data.guests

import com.vknxworks.proxctrl.data.api.PveClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.URLDecoder
import java.net.URLEncoder

/** A VM's cloud-init settings, from GET {base}/config. */
data class CloudInitConfig(
    /** A cloud-init drive is attached; without one the settings are stored but never reach the guest. */
    val hasDrive: Boolean,
    val user: String?,
    /** Proxmox never sends the password back, only whether one is set. */
    val passwordSet: Boolean,
    /** Public keys, one per line (decoded). */
    val sshKeys: String,
    val nameserver: String?,
    val searchDomain: String?,
    /** Upgrade packages on first boot; on unless turned off. */
    val upgrade: Boolean,
    /** Custom snippets replace the generated files; shown, not edited. */
    val custom: String?,
    /** One entry per network device: "ipconfig0" to its value ("ip=dhcp", "ip=10.0.0.5/24,gw=10.0.0.1"), or null. */
    val ipConfigs: List<Pair<String, String?>>,
    val digest: String?,
)

/**
 * Cloud-init settings of a VM (qemu-server, PVE::API2::Qemu): they are ordinary configuration keys
 * (ciuser, cipassword, sshkeys, ipconfigN, nameserver, searchdomain, ciupgrade), changed with
 * PUT {base}/config and needing VM.Config.Cloudinit on /vms/{vmid}. GET {base}/cloudinit lists what
 * differs from the image the guest last got, and PUT {base}/cloudinit writes a new image.
 */
object CloudInitApi {
    const val PRIV = "VM.Config.Cloudinit"

    val KEYS = setOf("ciuser", "cipassword", "sshkeys", "ciupgrade", "citype", "cicustom")
    val IPCONFIG = Regex("^ipconfig(\\d+)$")
    private val NET = Regex("^net(\\d+)$")
    private val DRIVE = Regex("^(ide|sata|scsi|virtio)\\d+$")

    /** True when one of the VM's drives is a cloud-init image ("local-lvm:vm-100-cloudinit,media=cdrom"). */
    fun hasDrive(o: JsonObject): Boolean =
        o.keys.any { key -> DRIVE.matches(key) && o.str(key)?.substringBefore(',')?.contains("cloudinit") == true }

    fun parse(o: JsonObject): CloudInitConfig {
        val indices = (o.keys.mapNotNull { NET.find(it)?.groupValues?.get(1)?.toIntOrNull() } +
            o.keys.mapNotNull { IPCONFIG.find(it)?.groupValues?.get(1)?.toIntOrNull() }).distinct().sorted()
        return CloudInitConfig(
            hasDrive = hasDrive(o),
            user = o.str("ciuser")?.takeIf { it.isNotBlank() },
            passwordSet = !o.str("cipassword").isNullOrEmpty(),
            sshKeys = decodeKeys(o.str("sshkeys").orEmpty()),
            nameserver = o.str("nameserver")?.takeIf { it.isNotBlank() },
            searchDomain = o.str("searchdomain")?.takeIf { it.isNotBlank() },
            upgrade = o.str("ciupgrade")?.let { it != "0" } ?: true,
            custom = o.str("cicustom")?.takeIf { it.isNotBlank() },
            ipConfigs = indices.map { i -> "ipconfig$i" to o.str("ipconfig$i")?.takeIf { it.isNotBlank() } },
            digest = o.str("digest"),
        )
    }

    /** Proxmox stores the keys URL-encoded (as encodeURIComponent does). */
    fun decodeKeys(stored: String): String =
        if (stored.isBlank()) "" else runCatching { URLDecoder.decode(stored.replace("+", "%2B"), "UTF-8") }.getOrDefault(stored).trim()

    fun encodeKeys(keys: String): String {
        val lines = keys.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        return URLEncoder.encode(lines, "UTF-8").replace("+", "%20")
    }

    suspend fun load(client: PveClient, ref: GuestRef): CloudInitConfig = parse(ResourcesApi.rawConfig(client, ref))

    /** The settings that changed since the guest's cloud-init image was last written. */
    suspend fun pendingKeys(client: PveClient, ref: GuestRef): List<String> =
        (client.get("${ref.base}/cloudinit") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { it["pending"] != null || (it.long("delete") ?: 0L) != 0L }
            .mapNotNull { it.str("key") }

    /** Sets [key] to [value]; a blank value removes the setting. */
    suspend fun set(client: PveClient, ref: GuestRef, key: String, value: String, digest: String?) {
        val params = buildMap {
            if (value.isBlank()) put("delete", key) else put(key, value)
            if (!digest.isNullOrBlank()) put("digest", digest)
        }
        client.put("${ref.base}/config", params)
    }

    /** Writes the cloud-init image again with the current settings. */
    suspend fun regenerate(client: PveClient, ref: GuestRef) {
        client.put("${ref.base}/cloudinit")
    }
}
