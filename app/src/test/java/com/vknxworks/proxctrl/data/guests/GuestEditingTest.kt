package com.vknxworks.proxctrl.data.guests

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The form fields and parsing behind firewall rule editing, guest-agent commands and cloud-init. */
class GuestEditingTest {

    private fun rule(
        pos: Int = 2, type: String = "in", action: String = "ACCEPT", enabled: Boolean = true, macro: String? = null,
        proto: String? = null, source: String? = null, dest: String? = null, dport: String? = null, sport: String? = null,
        iface: String? = null, comment: String? = null, icmpType: String? = null, digest: String? = "abc",
    ) = FirewallRule(pos, type, action, enabled, macro, proto, source, dest, dport, sport, iface, comment, null, icmpType, digest)

    @Test
    fun `a new rule sends only what was filled in`() {
        val p = FirewallApi.ruleParams(RuleDraft(type = "in", action = "DROP", enabled = false, proto = "tcp", dport = "22", comment = " ssh "), null)
        assertEquals(mapOf("type" to "in", "action" to "DROP", "enable" to "0", "proto" to "tcp", "dport" to "22", "comment" to "ssh"), p)
    }

    @Test
    fun `a macro drops protocol and ports`() {
        val p = FirewallApi.ruleParams(RuleDraft(macro = "SSH", proto = "tcp", dport = "22", sport = "1024:65535"), null)
        assertEquals("SSH", p["macro"])
        assertFalse("proto" in p)
        assertFalse("dport" in p)
        assertFalse("sport" in p)
    }

    @Test
    fun `ports without a protocol are not sent`() {
        val p = FirewallApi.ruleParams(RuleDraft(dport = "80"), null)
        assertFalse("dport" in p)
    }

    @Test
    fun `an edit deletes what was cleared and sends the digest`() {
        val original = rule(proto = "tcp", dport = "22", source = "10.0.0.0/24", comment = "old")
        val p = FirewallApi.ruleParams(RuleDraft.from(original).copy(source = "", comment = ""), original)
        assertEquals("tcp", p["proto"])
        assertEquals("22", p["dport"])
        assertEquals("source,comment", p["delete"])
        assertEquals("abc", p["digest"])
        assertEquals("1", p["enable"])
    }

    @Test
    fun `switching to a macro deletes the old protocol and ports`() {
        val original = rule(proto = "tcp", dport = "22")
        val p = FirewallApi.ruleParams(RuleDraft.from(original).copy(macro = "SSH"), original)
        assertEquals("SSH", p["macro"])
        assertEquals("proto,dport", p["delete"])
    }

    @Test
    fun `an icmp type goes when the protocol is no longer icmp`() {
        val original = rule(proto = "icmp", icmpType = "echo-request")
        assertNull(FirewallApi.ruleParams(RuleDraft.from(original), original)["delete"])
        assertEquals("icmp-type", FirewallApi.ruleParams(RuleDraft.from(original).copy(proto = "tcp"), original)["delete"])
    }

    @Test
    fun `moving one place`() {
        // Proxmox puts the rule in front of the one at "moveto": [a, b, c], a down -> moveto 2 -> [b, a, c].
        assertEquals(2, FirewallApi.moveTarget(0, up = false))
        assertEquals(0, FirewallApi.moveTarget(1, up = true))
    }

    @Test
    fun `rules keep their digest and log level`() {
        val data = Json.parseToJsonElement("""[{"pos":0,"type":"in","action":"ACCEPT","enable":1,"macro":"SSH","log":"info","digest":"d1"},{"pos":1,"type":"group","action":"web","enable":0}]""")
        val rules = ResourcesApi.parseRules(data)
        assertEquals("d1", rules[0].digest)
        assertEquals("info", rules[0].log)
        assertTrue(rules[1].isGroup)
        assertFalse(rules[1].enabled)
    }

    @Test
    fun `a command line is split like a shell would, without touching backslashes`() {
        assertEquals(listOf("ls", "-la", "/var/log"), AgentApi.splitCommand("  ls -la   /var/log "))
        assertEquals(listOf("sh", "-c", "df -h | tail -n 2"), AgentApi.splitCommand("sh -c \"df -h | tail -n 2\""))
        assertEquals(listOf("echo", "it's", "a \"b\""), AgentApi.splitCommand("echo \"it's\" 'a \"b\"'"))
        assertEquals(listOf("C:\\Windows\\System32\\ipconfig.exe", "/all"), AgentApi.splitCommand("C:\\Windows\\System32\\ipconfig.exe /all"))
        assertEquals(listOf("echo", "say \"hi\""), AgentApi.splitCommand("echo \"say \\\"hi\\\"\""))
        assertEquals(listOf("printf", ""), AgentApi.splitCommand("printf ''"))
        assertEquals(emptyList<String>(), AgentApi.splitCommand("   "))
    }

    @Test
    fun `a command's result`() {
        val r = ExecResult.parse(Json.parseToJsonElement("""{"exited":1,"exitcode":2,"out-data":"a\n","err-data":"oops","err-truncated":1}"""))
        assertTrue(r.exited)
        assertEquals(2, r.exitCode)
        assertEquals("a\n", r.out)
        assertTrue(r.errTruncated)
        assertFalse(r.outTruncated)
        assertFalse(ExecResult.parse(Json.parseToJsonElement("""{"exited":0}""")).exited)
    }

    @Test
    fun `cloud-init settings are read from the configuration`() {
        val o = Json.parseToJsonElement(
            """{"ide2":"local-lvm:vm-100-cloudinit,media=cdrom","scsi0":"local-lvm:vm-100-disk-0,size=8G","net0":"virtio=AA:BB,bridge=vmbr0","net1":"virtio=CC:DD,bridge=vmbr1",
               "ciuser":"axel","cipassword":"**********","sshkeys":"ssh-ed25519%20AAAA%2Bb%2Fc%20me%40pc%0Assh-rsa%20BBBB%20other","ipconfig0":"ip=dhcp","ciupgrade":0,"digest":"dg"}""",
        ).jsonObject
        val c = CloudInitApi.parse(o)
        assertTrue(c.hasDrive)
        assertEquals("axel", c.user)
        assertTrue(c.passwordSet)
        assertEquals("ssh-ed25519 AAAA+b/c me@pc\nssh-rsa BBBB other", c.sshKeys)
        assertEquals(listOf("ipconfig0" to "ip=dhcp", "ipconfig1" to null), c.ipConfigs)
        assertFalse(c.upgrade)
        assertEquals("dg", c.digest)
        assertTrue(GuestConfig.parse(o).hasCloudInit)
    }

    @Test
    fun `a vm without cloud-init`() {
        val o = Json.parseToJsonElement("""{"scsi0":"local-lvm:vm-103-disk-0,size=32G","net0":"virtio=AA:BB,bridge=vmbr0"}""").jsonObject
        assertFalse(CloudInitApi.parse(o).hasDrive)
        assertTrue(CloudInitApi.parse(o).upgrade)
        assertFalse(GuestConfig.parse(o).hasCloudInit)
    }

    @Test
    fun `ssh keys survive encoding and decoding`() {
        val keys = "ssh-ed25519 AAAA+b/c= me@pc\n\n  ssh-rsa BBBB other  \n"
        val encoded = CloudInitApi.encodeKeys(keys)
        assertFalse(encoded.contains('+'))
        assertFalse(encoded.contains(' '))
        assertEquals("ssh-ed25519 AAAA+b/c= me@pc\nssh-rsa BBBB other", CloudInitApi.decodeKeys(encoded))
    }
}
