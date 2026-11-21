package com.vknxworks.proxctrl.data.node

import com.vknxworks.proxctrl.data.guests.Guest
import com.vknxworks.proxctrl.data.guests.GuestType
import com.vknxworks.proxctrl.data.storage.BackupJobApi
import com.vknxworks.proxctrl.data.storage.JobEdit
import com.vknxworks.proxctrl.data.storage.JobSelection
import com.vknxworks.proxctrl.data.storage.StorageApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PCI passthrough matching, OCI references and backup-job edits. */
class PciAndJobsTest {

    private fun guest(type: GuestType, vmid: Int, name: String, running: Boolean = true) = Guest(
        type = type, vmid = vmid, name = name, node = "VKLab", status = if (running) "running" else "stopped", isTemplate = false,
        cpu = 0.0, maxCpu = 1.0, mem = 0, maxMem = 0, disk = 0, maxDisk = 0, uptime = 0, tags = emptyList(), haState = "", lock = "",
    )

    private val devices = PciApi.parseDevices(
        Json.parseToJsonElement(
            """[{"id":"0000:01:00.0","class":"0x030000","vendor":"0x10de","device":"0x2204","vendor_name":"NVIDIA Corporation","device_name":"GA102 [GeForce RTX 3090]","iommugroup":14},
                {"id":"0000:01:00.1","class":"0x040300","vendor":"0x10de","device":"0x1aef","vendor_name":"NVIDIA Corporation","device_name":"GA102 High Definition Audio Controller","iommugroup":14},
                {"id":"0000:06:00.0","class":"0x030000","vendor":"0x10de","device":"0x1f82","vendor_name":"NVIDIA Corporation","device_name":"TU117 [GeForce GTX 1650]","iommugroup":20},
                {"id":"0000:04:00.0","class":"0x020000","vendor":"0x8086","device":"0x15f3","iommugroup":-1}]""",
        ),
    )

    @Test
    fun `hostpci lines`() {
        assertEquals(HostPci(listOf("0000:01:00"), null), PciApi.parseHostPci("0000:01:00,pcie=1,x-vga=1"))
        assertEquals(HostPci(listOf("01:00.0", "01:00.1"), null), PciApi.parseHostPci("host=01:00.0;01:00.1,rombar=0"))
        assertEquals(HostPci(emptyList(), "gpu"), PciApi.parseHostPci("mapping=gpu,pcie=1"))
    }

    @Test
    fun `addresses match with or without domain and function`() {
        assertTrue(PciApi.matches("0000:01:00.0", "01:00"))
        assertTrue(PciApi.matches("0000:01:00.1", "0000:01:00"))
        assertTrue(PciApi.matches("0000:01:00.0", "01:00.0"))
        assertFalse(PciApi.matches("0000:01:00.1", "01:00.0"))
        assertFalse(PciApi.matches("0000:06:00.0", "01:00"))
        assertFalse(PciApi.matches("0001:01:00.0", "01:00.0"))
        assertFalse(PciApi.matches("0000:01:00.0", "nonsense"))
    }

    @Test
    fun `a card goes to the vm that names it and containers list their device files`() {
        val vm = guest(GuestType.QEMU, 129, "Windows-11") to Json.parseToJsonElement("""{"hostpci0":"0000:01:00,pcie=1,x-vga=1","scsi0":"x"}""").jsonObject
        val ct = guest(GuestType.LXC, 130, "frigate") to Json.parseToJsonElement(
            """{"dev0":"/dev/nvidia0,gid=44","dev1":"path=/dev/nvidiactl","lxc":[["lxc.cgroup2.devices.allow","c 195:* rwm"],["lxc.mount.entry","/dev/nvidia-uvm dev/nvidia-uvm none bind,optional,create=file"],["lxc.mount.entry","/mnt/media mnt/media none bind"]]}""",
        ).jsonObject
        val plain = guest(GuestType.LXC, 101, "ntfy") to Json.parseToJsonElement("""{"rootfs":"local-zfs:subvol-101-disk-0,size=4G"}""").jsonObject
        val o = PciApi.combine(devices, emptyMap(), listOf(vm, ct, plain), unreadable = 0)
        assertEquals(listOf(129), o.uses["0000:01:00.0"]?.map { it.vmid })
        assertEquals(listOf(129), o.uses["0000:01:00.1"]?.map { it.vmid })
        assertEquals(null, o.uses["0000:06:00.0"])
        assertTrue(o.unmatched.isEmpty())
        assertEquals(1, o.containers.size)
        assertEquals(listOf("/dev/nvidia0", "/dev/nvidiactl", "/dev/nvidia-uvm"), o.containers[0].paths)
        assertTrue(devices.first { it.id == "0000:06:00.0" }.isGpu)
        assertFalse(devices.first { it.id == "0000:01:00.1" }.isGpu)
        assertEquals("8086:15f3", devices.first { it.id == "0000:04:00.0" }.label)
    }

    @Test
    fun `mappings resolve for this node only and unknown ones are listed`() {
        val mappings = PciApi.parseMappings(
            Json.parseToJsonElement("""[{"id":"gpu","map":["id=10de:2204,iommugroup=14,node=VKLab,path=0000:01:00.0","id=10de:2204,node=other,path=0000:09:00.0"]}]"""),
            "VKLab",
        )
        assertEquals(mapOf("gpu" to listOf("0000:01:00.0")), mappings)
        val mapped = guest(GuestType.QEMU, 107, "Immich") to Json.parseToJsonElement("""{"hostpci0":"mapping=gpu"}""").jsonObject
        val lost = guest(GuestType.QEMU, 106, "HAOS", running = false) to Json.parseToJsonElement("""{"hostpci1":"mapping=usbcard"}""").jsonObject
        val o = PciApi.combine(devices, mappings, listOf(mapped, lost), unreadable = 2)
        assertEquals("gpu", o.uses["0000:01:00.0"]?.single()?.mapping)
        assertEquals(listOf(106), o.unmatched.map { it.vmid })
        assertEquals(2, o.unreadable)
    }

    @Test
    fun `oci references`() {
        listOf("docker.io/library/debian:13", "ghcr.io/home-assistant/home-assistant:stable", "nginx:latest", "registry.example.com:5000/team/app_1:v1.2-rc1")
            .forEach { assertTrue(it, StorageApi.OCI_REFERENCE.matches(it)) }
        listOf("nginx", "docker.io/library/debian", "Nginx:latest", "nginx:", "https://docker.io/nginx:1", "nginx:la test")
            .forEach { assertFalse(it, StorageApi.OCI_REFERENCE.matches(it)) }
        assertTrue(StorageApi.supportsOci("9.2.18"))
        assertTrue(StorageApi.supportsOci("9.1"))
        assertTrue(StorageApi.supportsOci("10.0.1"))
        assertFalse(StorageApi.supportsOci("9.0.11"))
        assertFalse(StorageApi.supportsOci("8.4.1"))
        assertFalse(StorageApi.supportsOci(""))
    }

    private fun job(json: String) = BackupJobApi.parse(Json.parseToJsonElement("[$json]")).single()

    @Test
    fun `a job edit sends only what changed`() {
        val j = job("""{"id":"backup-1","enabled":1,"schedule":"21:00","storage":"pbs","mode":"snapshot","compress":"zstd","all":1,"comment":"Nightly"}""")
        val same = JobEdit.from(j)
        assertEquals(mapOf("enabled" to "1"), BackupJobApi.updateParams(j, same))
        assertEquals(
            mapOf("enabled" to "0", "schedule" to "sat 03:00", "mode" to "stop", "compress" to "0", "delete" to "comment"),
            BackupJobApi.updateParams(j, same.copy(enabled = false, schedule = " sat 03:00 ", comment = " ", mode = "stop", compress = "0")),
        )
    }

    @Test
    fun `changing which guests a job covers`() {
        val all = job("""{"id":"a","schedule":"21:00","all":1}""")
        assertEquals("100,105", BackupJobApi.updateParams(all, JobEdit.from(all).copy(selection = JobSelection.Guests(listOf("100", "105"))))["vmid"])
        val except = BackupJobApi.updateParams(all, JobEdit.from(all).copy(selection = JobSelection.AllExcept(listOf("103"))))
        assertEquals("1", except["all"])
        assertEquals("103", except["exclude"])

        val excluding = job("""{"id":"b","schedule":"21:00","all":1,"exclude":"103,106"}""")
        val back = BackupJobApi.updateParams(excluding, JobEdit.from(excluding).copy(selection = JobSelection.All))
        assertEquals("1", back["all"])
        assertEquals("exclude", back["delete"])

        val pool = job("""{"id":"c","schedule":"21:00","pool":"prod"}""")
        assertEquals(mapOf("enabled" to "1"), BackupJobApi.updateParams(pool, JobEdit.from(pool)))
    }

    @Test
    fun `run now sends a list option once per entry`() {
        val j = job("""{"id":"d","schedule":"21:00","vmid":"100","storage":"local","exclude-path":["/tmp","/var/cache"],"compress":"1"}""")
        val fields = BackupJobApi.runFields(j.options)
        assertEquals(listOf("/tmp", "/var/cache"), fields.filter { it.first == "exclude-path" }.map { it.second })
        assertEquals(listOf("100"), fields.filter { it.first == "vmid" }.map { it.second })
        assertEquals("lzo", j.compress)
    }
}

/** What only applies to clusters: status parsing and the fields a migration sends. */
class ClusterTest {
    private val api = com.vknxworks.proxctrl.data.cluster.ClusterApi

    @Test
    fun `a single node has no cluster entry`() {
        val alone = api.parseState(Json.parseToJsonElement("""[{"type":"node","name":"VKLab","online":1,"local":1,"ip":"192.168.1.10","nodeid":0}]"""))
        assertEquals(null, alone.name)
        assertTrue(alone.quorate)
        assertEquals(listOf("VKLab"), alone.members.map { it.name })
        val two = api.parseState(
            Json.parseToJsonElement("""[{"type":"cluster","name":"home","nodes":2,"quorate":0},{"type":"node","name":"b","online":0},{"type":"node","name":"a","online":1,"local":1}]"""),
        )
        assertEquals("home", two.name)
        assertFalse(two.quorate)
        assertEquals(listOf("a", "b"), two.members.map { it.name })
        assertFalse(two.members[1].online)
    }

    @Test
    fun `what a migration sends`() {
        assertEquals(mapOf("target" to "b"), api.migrateParams(GuestType.QEMU, "b", running = false, withLocalDisks = true))
        assertEquals(mapOf("target" to "b", "online" to "1"), api.migrateParams(GuestType.QEMU, "b", running = true, withLocalDisks = false))
        assertEquals(mapOf("target" to "b", "online" to "1", "with-local-disks" to "1"), api.migrateParams(GuestType.QEMU, "b", running = true, withLocalDisks = true))
        assertEquals(mapOf("target" to "b", "restart" to "1"), api.migrateParams(GuestType.LXC, "b", running = true, withLocalDisks = true))
        assertEquals(mapOf("target" to "b"), api.migrateParams(GuestType.LXC, "b", running = false, withLocalDisks = false))
    }

    @Test
    fun `replication and ceph status`() {
        val jobs = api.parseReplication(Json.parseToJsonElement("""[{"id":"100-0","guest":100,"target":"b","schedule":"*/15","last_sync":1700000000,"next_sync":1700000900,"fail_count":2,"error":"timeout"}]"""))
        assertEquals(2, jobs.single().failCount)
        assertEquals("timeout", jobs.single().error)
        val ceph = api.parseCeph(
            Json.parseToJsonElement(
                """{"health":{"status":"HEALTH_WARN","checks":{"OSD_DOWN":{"severity":"HEALTH_WARN","summary":{"message":"1 osds down"}}}},"pgmap":{"bytes_used":10,"bytes_total":40},"osdmap":{"num_osds":3,"num_up_osds":2,"num_in_osds":3}}""",
            ),
        )
        assertEquals("HEALTH_WARN", ceph.health)
        assertEquals(listOf("OSD_DOWN: 1 osds down"), ceph.checks)
        assertEquals(40L, ceph.totalBytes)
        assertEquals(2, ceph.osdsUp)
        val old = api.parseCeph(Json.parseToJsonElement("""{"health":{"status":"HEALTH_OK"},"osdmap":{"osdmap":{"num_osds":6,"num_up_osds":6,"num_in_osds":6}}}"""))
        assertEquals(6, old.osds)
    }
}
