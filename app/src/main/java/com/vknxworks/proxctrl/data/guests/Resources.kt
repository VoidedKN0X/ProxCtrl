package com.vknxworks.proxctrl.data.guests

import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.api.PveError
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One disk of a VM, or the root disk or a mount point of a container. */
data class GuestDisk(
    /** Config key: scsi0, virtio1, sata0, efidisk0, rootfs, mp0… */
    val key: String,
    /** Volume, e.g. "local-zfs:vm-107-disk-0", or a host path for bind mounts. */
    val volume: String,
    /** Size in bytes, if Proxmox recorded one. */
    val size: Long?,
    /** Containers: where it's mounted inside the guest. */
    val mountPoint: String?,
) {
    val storage: String? get() = volume.substringBefore(':', "").takeIf { it.isNotBlank() && !volume.startsWith("/") }

    /** EFI and TPM state disks have a fixed size; bind mounts have no volume to grow. */
    val resizable: Boolean get() = storage != null && size != null && !key.startsWith("efidisk") && !key.startsWith("tpmstate")
}

/** One network device. */
data class GuestNic(val key: String, val model: String?, val mac: String?, val bridge: String?, val vlan: String?, val firewall: Boolean, val ip: String?, val name: String?)

/** The hardware the guest page shows and edits. Memory values are MiB, as Proxmox stores them. */
data class GuestResources(
    val cores: Int,
    val sockets: Int,
    val memoryMiB: Long,
    /** VMs: the ballooning minimum (0 = ballooning off), null when not set. */
    val balloonMiB: Long?,
    /** Containers: swap. */
    val swapMiB: Long?,
    val cpuType: String?,
    val disks: List<GuestDisk>,
    val nics: List<GuestNic>,
    val tags: List<String>,
    val digest: String?,
)

/** A guest's options the app shows read-only. Values are as Proxmox stores them. */
data class GuestOption(val key: String, val value: String)

/** Firewall settings of a guest: GET {base}/firewall/options. */
data class FirewallOptions(
    val enabled: Boolean,
    val policyIn: String?,
    val policyOut: String?,
    val other: List<GuestOption>,
)

/** One firewall rule: GET {base}/firewall/rules. */
data class FirewallRule(
    val pos: Int,
    val type: String,
    val action: String,
    val enabled: Boolean,
    val macro: String?,
    val proto: String?,
    val source: String?,
    val dest: String?,
    val dport: String?,
    val sport: String?,
    val iface: String?,
    val comment: String?,
)

/**
 * Hardware, options and firewall of a guest. Editing cores needs VM.Config.CPU, memory and swap need
 * VM.Config.Memory, tags need VM.Config.Options; growing a disk needs VM.Config.Disk (and space on the
 * storage). Cloning needs VM.Clone on the guest and VM.Allocate on the new ID; turning a guest into a
 * template needs VM.Allocate.
 */
object ResourcesApi {
    const val PRIV_CPU = "VM.Config.CPU"
    const val PRIV_MEMORY = "VM.Config.Memory"
    const val PRIV_OPTIONS = "VM.Config.Options"
    const val PRIV_DISK = "VM.Config.Disk"
    const val PRIV_CLONE = "VM.Clone"
    const val PRIV_ALLOCATE = "VM.Allocate"

    private val VM_DISK = Regex("^(ide|sata|scsi|virtio|efidisk|tpmstate)\\d+$")
    private val LXC_DISK = Regex("^(rootfs|mp\\d+)$")
    private val NIC = Regex("^net\\d+$")
    private val MODELS = setOf("virtio", "e1000", "e1000e", "rtl8139", "vmxnet3", "i82551", "i82557b", "i82559er", "ne2k_isa", "ne2k_pci", "pcnet")

    suspend fun rawConfig(client: PveClient, ref: GuestRef): JsonObject =
        client.get("${ref.base}/config") as? JsonObject ?: JsonObject(emptyMap())

    /** "key=value,key=value" with an optional leading value without a key. */
    fun props(value: String, firstKey: String): Map<String, String> =
        value.split(',').mapIndexedNotNull { i, part ->
            val kv = part.split('=', limit = 2)
            when {
                kv.size == 2 -> kv[0].trim() to kv[1].trim()
                i == 0 && part.isNotBlank() -> firstKey to part.trim()
                else -> null
            }
        }.toMap()

    /** "32G", "512M", "1T", "100K" or plain bytes. */
    fun parseSize(s: String?): Long? {
        val v = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val unit = v.last().uppercaseChar()
        val number = if (unit.isLetter()) v.dropLast(1) else v
        val n = number.toDoubleOrNull() ?: return null
        val mult = when (unit) {
            'K' -> 1024.0
            'M' -> 1024.0 * 1024
            'G' -> 1024.0 * 1024 * 1024
            'T' -> 1024.0 * 1024 * 1024 * 1024
            else -> 1.0
        }
        return (n * mult).toLong()
    }

    fun parse(type: GuestType, o: JsonObject): GuestResources {
        val memory = o.str("memory")?.let { m -> props(m, "current")["current"]?.toLongOrNull() } ?: 512L
        val disks = o.keys.filter { if (type == GuestType.QEMU) VM_DISK.matches(it) else LXC_DISK.matches(it) }
            .mapNotNull { key ->
                val value = o.str(key) ?: return@mapNotNull null
                val p = props(value, "volume")
                // CD/DVD drives aren't disks.
                if (p["media"] == "cdrom" || p["volume"] == "none") return@mapNotNull null
                GuestDisk(key, p["volume"].orEmpty(), parseSize(p["size"]), p["mp"])
            }
            .sortedWith(compareBy({ it.key != "rootfs" }, { it.key.filter(Char::isLetter) }, { it.key.filter(Char::isDigit).toIntOrNull() ?: 0 }))
        val nics = o.keys.filter { NIC.matches(it) }.sortedBy { it.removePrefix("net").toIntOrNull() ?: 0 }.mapNotNull { key ->
            val p = props(o.str(key) ?: return@mapNotNull null, "model")
            val model = p.keys.firstOrNull { it in MODELS }
            GuestNic(
                key = key,
                model = model ?: p["model"],
                mac = (model?.let { p[it] } ?: p["hwaddr"] ?: p["macaddr"]),
                bridge = p["bridge"],
                vlan = p["tag"],
                firewall = p["firewall"] == "1",
                ip = listOfNotNull(p["ip"], p["ip6"]).joinToString(", ").ifBlank { null },
                name = p["name"],
            )
        }
        return GuestResources(
            cores = o.long("cores")?.toInt() ?: 1,
            sockets = o.long("sockets")?.toInt() ?: 1,
            memoryMiB = memory,
            balloonMiB = if (type == GuestType.QEMU) o.long("balloon") else null,
            swapMiB = if (type == GuestType.LXC) o.long("swap") ?: 512L else null,
            cpuType = o.str("cpu")?.let { props(it, "cputype")["cputype"] },
            disks = disks,
            nics = nics,
            tags = o.str("tags").orEmpty().split(';', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() },
            digest = o.str("digest"),
        )
    }

    /** The options shown read-only, in a sensible order. */
    fun options(type: GuestType, o: JsonObject): List<GuestOption> {
        val keys = when (type) {
            GuestType.QEMU -> listOf("name", "ostype", "onboot", "startup", "protection", "boot", "bios", "machine", "agent", "hotplug", "acpi", "kvm", "tablet", "localtime", "numa", "scsihw", "vga", "spice_enhancements", "keyboard", "freeze", "hookscript")
            GuestType.LXC -> listOf("hostname", "ostype", "arch", "unprivileged", "features", "onboot", "startup", "protection", "console", "tty", "cmode", "nameserver", "searchdomain", "timezone", "hookscript")
        }
        return keys.mapNotNull { k -> o.str(k)?.takeIf { it.isNotBlank() }?.let { GuestOption(k, it) } }
    }

    suspend fun resources(client: PveClient, ref: GuestRef): Pair<GuestResources, List<GuestOption>> {
        val o = rawConfig(client, ref)
        return parse(ref.type, o) to options(ref.type, o)
    }

    /**
     * Saves cores and memory (and swap for containers). Only changed values are sent. A running VM may
     * take some changes only at its next restart (Proxmox keeps them as pending).
     */
    suspend fun setCpuMemory(client: PveClient, ref: GuestRef, current: GuestResources, cores: Int, memoryMiB: Long, swapMiB: Long?) {
        val params = buildMap {
            if (cores != current.cores) put("cores", cores.toString())
            if (memoryMiB != current.memoryMiB) put("memory", memoryMiB.toString())
            if (ref.type == GuestType.LXC && swapMiB != null && swapMiB != current.swapMiB) put("swap", swapMiB.toString())
            if (!current.digest.isNullOrBlank()) put("digest", current.digest)
        }
        if (params.keys.any { it != "digest" }) client.put("${ref.base}/config", params)
    }

    suspend fun setTags(client: PveClient, ref: GuestRef, tags: List<String>, digest: String?) {
        val clean = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val params = buildMap {
            if (clean.isEmpty()) put("delete", "tags") else put("tags", clean.joinToString(";"))
            if (!digest.isNullOrBlank()) put("digest", digest)
        }
        client.put("${ref.base}/config", params)
    }

    /** Proxmox tag names: letters, digits and - _ + . only. */
    val TAG = Regex("^[a-z0-9_][a-z0-9_\\-+.]*$", RegexOption.IGNORE_CASE)

    /** Grows [disk] by [addGiB] GiB. Never shrinks: the size is always sent as "+N G". Returns a UPID or null. */
    suspend fun growDisk(client: PveClient, ref: GuestRef, disk: GuestDisk, addGiB: Int, digest: String?): String? {
        require(addGiB > 0)
        val params = buildMap {
            put("disk", disk.key)
            put("size", "+${addGiB}G")
            if (!digest.isNullOrBlank()) put("digest", digest)
        }
        return (client.put("${ref.base}/resize", params) as? JsonPrimitive)?.content
    }

    suspend fun nextId(client: PveClient): Int? = (client.get("/cluster/nextid") as? JsonPrimitive)?.content?.toIntOrNull()

    /**
     * Whether [vmid] is free: /cluster/nextid?vmid= answers with an error status when it's taken. A network
     * hiccup doesn't count as taken (Proxmox still checks when creating), and a check that was replaced by
     * a newer one (the ID was typed further) is cancelled instead of reporting "in use".
     */
    suspend fun isIdFree(client: PveClient, vmid: Int): Boolean = try {
        client.get("/cluster/nextid", mapOf("vmid" to vmid.toString()))
        true
    } catch (e: PveError.Server) {
        false
    } catch (e: PveError) {
        true
    }

    suspend fun clone(client: PveClient, ref: GuestRef, newId: Int, name: String, full: Boolean, storage: String?): String? {
        val params = buildMap {
            put("newid", newId.toString())
            if (name.isNotBlank()) put(if (ref.type == GuestType.LXC) "hostname" else "name", name.trim())
            put("full", if (full) "1" else "0")
            if (full && !storage.isNullOrBlank()) put("storage", storage)
        }
        return (client.post("${ref.base}/clone", params) as? JsonPrimitive)?.content
    }

    /** Turns the guest into a template (it must be stopped). Containers finish straight away (null). */
    suspend fun convertToTemplate(client: PveClient, ref: GuestRef): String? =
        (client.post("${ref.base}/template") as? JsonPrimitive)?.content

    suspend fun firewallOptions(client: PveClient, ref: GuestRef): FirewallOptions {
        val o = client.get("${ref.base}/firewall/options") as? JsonObject ?: JsonObject(emptyMap())
        val shown = setOf("enable", "policy_in", "policy_out", "digest")
        return FirewallOptions(
            enabled = (o.long("enable") ?: 0L) != 0L,
            policyIn = o.str("policy_in"),
            policyOut = o.str("policy_out"),
            other = o.keys.filter { it !in shown }.sorted().mapNotNull { k -> o.str(k)?.let { GuestOption(k, it) } },
        )
    }

    suspend fun firewallRules(client: PveClient, ref: GuestRef): List<FirewallRule> = parseRules(client.get("${ref.base}/firewall/rules"))

    fun parseRules(data: JsonElement?): List<FirewallRule> {
        val list = data as? JsonArray ?: return emptyList()
        return list.mapNotNull { it as? JsonObject }.map { o ->
            FirewallRule(
                pos = o.long("pos")?.toInt() ?: 0,
                type = o.str("type").orEmpty(),
                action = o.str("action").orEmpty(),
                enabled = (o.long("enable") ?: 0L) != 0L,
                macro = o.str("macro"),
                proto = o.str("proto"),
                source = o.str("source"),
                dest = o.str("dest"),
                dport = o.str("dport"),
                sport = o.str("sport"),
                iface = o.str("iface"),
                comment = o.str("comment"),
            )
        }.sortedBy { it.pos }
    }
}
