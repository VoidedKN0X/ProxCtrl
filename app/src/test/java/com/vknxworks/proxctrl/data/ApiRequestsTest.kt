package com.vknxworks.proxctrl.data

import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.api.PveError
import com.vknxworks.proxctrl.data.api.PveJson
import com.vknxworks.proxctrl.data.guests.GuestRef
import com.vknxworks.proxctrl.data.guests.GuestType
import com.vknxworks.proxctrl.data.guests.PowerAction
import com.vknxworks.proxctrl.data.guests.SnapshotApi
import com.vknxworks.proxctrl.data.guests.power
import com.vknxworks.proxctrl.data.server.AuthMethod
import com.vknxworks.proxctrl.data.server.Server
import com.vknxworks.proxctrl.data.server.ServerSecrets
import com.vknxworks.proxctrl.data.widgets.GuestTileLogic
import com.vknxworks.proxctrl.data.widgets.GuestTileTarget
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The requests the snapshot screen and the guest tile send, against a fake Proxmox VE: an OkHttp
 * interceptor that records each request and answers with recorded responses. Nothing leaves the computer.
 */
class ApiRequestsTest {

    private class Fake(private val answer: (Request) -> Pair<Int, String>) : Interceptor {
        val requests = mutableListOf<Pair<Request, String?>>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val r = chain.request()
            val body = r.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }
            requests += r to body
            val (code, text) = answer(r)
            return Response.Builder()
                .request(r)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 403) "Permission check failed (/vms/101, VM.Snapshot)" else "OK")
                .body(text.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private val server = Server(
        id = "s1",
        name = "Home",
        url = "https://pve.example.test:8006",
        auth = AuthMethod.TOKEN,
        tokenId = "proxctrl@pve!phone",
    )

    private fun client(fake: Fake, s: Server = server) =
        PveClient(s, OkHttpClient.Builder().addInterceptor(fake).build(), ServerSecrets(tokenSecret = "00000000-test"))

    private val upid = "\"UPID:pve:0001E0F2:02A5C1B3:6734A1B2:qmsnapshot:101:proxctrl@pve!phone:\""
    private val vm = GuestRef("pve", GuestType.QEMU, 101)

    @Test
    fun takeSnapshotOnTheWire() = runBlocking {
        val fake = Fake { 200 to """{"data":$upid}""" }
        val task = SnapshotApi.take(client(fake), vm, "before_upgrade", "Before apt upgrade", withRam = true)
        assertEquals("UPID:pve:0001E0F2:02A5C1B3:6734A1B2:qmsnapshot:101:proxctrl@pve!phone:", task)
        val (r, body) = fake.requests.single()
        assertEquals("POST", r.method)
        assertEquals("https://pve.example.test:8006/api2/json/nodes/pve/qemu/101/snapshot", r.url.toString())
        assertEquals("snapname=before_upgrade&description=Before%20apt%20upgrade&vmstate=1", body)
        assertEquals("PVEAPIToken=proxctrl@pve!phone=00000000-test", r.header("Authorization"))
    }

    @Test
    fun rollbackAndDeleteOnTheWire() = runBlocking {
        val fake = Fake { 200 to """{"data":$upid}""" }
        val api = client(fake)
        SnapshotApi.rollback(api, vm, "before_upgrade", start = true)
        SnapshotApi.rollback(api, vm, "before_upgrade", start = false)
        SnapshotApi.delete(api, GuestRef("pve2", GuestType.LXC, 200), "s1")
        val (rb, rbBody) = fake.requests[0]
        assertEquals("POST", rb.method)
        assertEquals("/api2/json/nodes/pve/qemu/101/snapshot/before_upgrade/rollback", rb.url.encodedPath)
        assertEquals("start=1", rbBody)
        assertEquals("", fake.requests[1].second)
        val (del, delBody) = fake.requests[2]
        assertEquals("DELETE", del.method)
        assertEquals("/api2/json/nodes/pve2/lxc/200/snapshot/s1", del.url.encodedPath)
        assertNull(delBody)
    }

    @Test
    fun listOnTheWireAndAMissingPrivilege() = runBlocking {
        val ok = Fake { 200 to """{"data":[{"name":"current","running":0,"description":"You are here!"},{"name":"s1","snaptime":1730000000}]}""" }
        val list = SnapshotApi.list(client(ok), vm)
        assertEquals("GET", ok.requests.single().first.method)
        assertEquals("/api2/json/nodes/pve/qemu/101/snapshot", ok.requests.single().first.url.encodedPath)
        assertEquals(listOf("current", "s1"), list.map { it.name })

        // A token without the privilege: Proxmox answers 403 with the reason, which the screen turns into a clear message.
        val denied = Fake { 403 to """{"data":null}""" }
        try {
            SnapshotApi.take(client(denied), vm, "s2", "", withRam = false)
            fail("403 must be an error")
        } catch (e: PveError.Forbidden) {
            assertEquals("Permission check failed (/vms/101, VM.Snapshot)", e.detail)
        }
    }

    @Test
    fun readOnlyServersSendNothing() = runBlocking {
        val fake = Fake { 200 to """{"data":$upid}""" }
        try {
            SnapshotApi.delete(client(fake, server.copy(readOnly = true)), vm, "s1")
            fail("read-only must refuse")
        } catch (_: PveError.ReadOnly) {
        }
        assertTrue(fake.requests.isEmpty())
    }

    /** Recorded GET /cluster/resources?type=vm "data" (shortened). */
    private val resources = """
        {"data":[
          {"id":"qemu/101","type":"qemu","vmid":101,"name":"web","node":"pve2","status":"running","template":0,"maxmem":4294967296,"mem":1073741824,"uptime":3600},
          {"id":"lxc/101","type":"lxc","vmid":101,"name":"same-id-other-type","node":"pve","status":"stopped"},
          {"id":"lxc/200","type":"lxc","vmid":200,"name":"dns","node":"pve","status":"stopped","template":0},
          {"id":"qemu/300","type":"qemu","vmid":300,"name":"backup","node":"pve","status":"running","lock":"backup"},
          {"id":"qemu/9000","type":"qemu","vmid":9000,"name":"tmpl","node":"pve","status":"stopped","template":1},
          {"id":"qemu/400","type":"qemu","vmid":400,"name":"paused","node":"pve","status":"running"}
        ]}
    """.trimIndent()

    @Test
    fun guestTileFindsItsGuestAndActs() = runBlocking {
        val fake = Fake { r ->
            if (r.url.encodedPath.endsWith("/cluster/resources")) 200 to resources else 200 to """{"data":$upid}"""
        }
        val api = client(fake)
        val data = api.get(GuestTileLogic.RESOURCES_PATH, GuestTileLogic.RESOURCES_QUERY)
        assertEquals("type=vm", fake.requests[0].first.url.encodedQuery)

        // Found by type and ID, wherever it runs now (moved to node pve2).
        val web = GuestTileLogic.find(data, GuestTileTarget("s1", "qemu", 101, "web"))!!
        assertEquals("pve2", web.node)
        assertEquals(PowerAction.SHUTDOWN, GuestTileLogic.tapAction(web))
        val dns = GuestTileLogic.find(data, GuestTileTarget("s1", "lxc", 200))!!
        assertEquals(PowerAction.START, GuestTileLogic.tapAction(dns))
        // A guest locked by a backup or a template: no action from the tile.
        assertNull(GuestTileLogic.tapAction(GuestTileLogic.find(data, GuestTileTarget("s1", "qemu", 300))))
        assertNull(GuestTileLogic.tapAction(GuestTileLogic.find(data, GuestTileTarget("s1", "qemu", 9000))))
        assertNull(GuestTileLogic.find(data, GuestTileTarget("s1", "qemu", 999)))
        assertNull(GuestTileLogic.tapAction(null))
        // The picker leaves templates out.
        assertEquals(listOf(101, 101, 200, 300, 400), GuestTileLogic.pickable(data).map { it.vmid })

        // Start and the graceful shut-down, on the node the guest is on, without parameters.
        assertEquals("/nodes/pve2/qemu/101/status/shutdown", GuestTileLogic.powerPath(web, PowerAction.SHUTDOWN))
        val shut = api.power(web, PowerAction.SHUTDOWN)
        assertNull(shut.error)
        assertTrue(shut.upid!!.startsWith("UPID:pve:"))
        api.power(dns, PowerAction.START)
        val (s, sBody) = fake.requests[1]
        assertEquals("POST", s.method)
        assertEquals("/api2/json/nodes/pve2/qemu/101/status/shutdown", s.url.encodedPath)
        assertEquals("", sBody)
        assertEquals("/api2/json/nodes/pve/lxc/200/status/start", fake.requests[2].first.url.encodedPath)
    }

    @Test
    fun guestTileTargetRoundTrip() {
        val t = GuestTileTarget("server-uuid", "lxc", 200, "dns")
        val json = PveJson.encodeToString(GuestTileTarget.serializer(), t)
        assertEquals(t, PveJson.decodeFromString(GuestTileTarget.serializer(), json))
        assertEquals(GuestType.LXC, t.guestType)
        // Unknown types fall back to a VM rather than failing.
        assertEquals(GuestType.QEMU, GuestTileTarget("s", "weird", 1).guestType)
    }
}
