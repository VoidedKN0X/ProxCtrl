package com.vknxworks.proxctrl.data.guests

import com.vknxworks.proxctrl.data.api.PveJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotsTest {

    @Test
    fun validNames() {
        listOf("ab", "before_upgrade", "snap_20261113_1200", "Upgrade2", "a".repeat(40), "A_1").forEach {
            assertNull("'$it' should be accepted", SnapshotApi.nameProblem(it))
            assertTrue(SnapshotApi.isValidName(it))
        }
    }

    @Test
    fun invalidNamesSayWhy() {
        assertEquals(SnapshotNameProblem.EMPTY, SnapshotApi.nameProblem(""))
        assertEquals(SnapshotNameProblem.TOO_SHORT, SnapshotApi.nameProblem("a"))
        assertEquals(SnapshotNameProblem.TOO_LONG, SnapshotApi.nameProblem("a".repeat(41)))
        assertEquals(SnapshotNameProblem.FIRST_NOT_LETTER, SnapshotApi.nameProblem("1abc"))
        assertEquals(SnapshotNameProblem.FIRST_NOT_LETTER, SnapshotApi.nameProblem("_abc"))
        // pve-configid allows no dashes, dots, spaces or non-ASCII letters.
        listOf("before-upgrade", "v1.2", "my snap", "café", "snap/1", "snap\$x").forEach {
            assertEquals("'$it'", SnapshotNameProblem.BAD_CHARACTER, SnapshotApi.nameProblem(it))
        }
        assertEquals(SnapshotNameProblem.RESERVED, SnapshotApi.nameProblem("current"))
        assertEquals(SnapshotNameProblem.RESERVED, SnapshotApi.nameProblem("pending"))
        assertEquals(SnapshotNameProblem.RESERVED, SnapshotApi.nameProblem("PENDING"))
        assertEquals(SnapshotNameProblem.TAKEN, SnapshotApi.nameProblem("before_upgrade", setOf("before_upgrade")))
        assertFalse(SnapshotApi.isValidName("current"))
        // Exactly what the regex says too.
        assertFalse(SnapshotApi.NAME.matches("before-upgrade"))
    }

    /** Recorded GET /nodes/pve/qemu/101/snapshot "data" (fields as Proxmox VE 8/9 send them). */
    private val recorded = """
        [
          {"name":"after_upgrade","parent":"before_upgrade","snaptime":1730500000,"description":"","vmstate":1},
          {"name":"current","parent":"after_upgrade","running":1,"description":"You are here!","digest":"5f1c"},
          {"name":"before_upgrade","snaptime":1730000000,"description":"Before apt upgrade\n"},
          {"name":"experiment","parent":"before_upgrade","snaptime":"1730300000","description":"try it"},
          {"description":"no name: skipped"}
        ]
    """.trimIndent()

    @Test
    fun parsesTheRecordedList() {
        val list = SnapshotApi.parse(PveJson.parseToJsonElement(recorded))
        assertEquals(listOf("after_upgrade", "current", "before_upgrade", "experiment"), list.map { it.name })
        val before = list.first { it.name == "before_upgrade" }
        assertEquals("Before apt upgrade", before.description)
        assertNull(before.parent)
        assertEquals(1730000000L, before.time)
        // A number sent as a string is still read.
        assertEquals(1730300000L, list.first { it.name == "experiment" }.time)
        assertTrue(list.first { it.name == "after_upgrade" }.withRam)
        val current = list.first { it.isCurrent }
        assertNull(current.time)
        assertEquals("after_upgrade", current.parent)
    }

    @Test
    fun buildsTheTree() {
        val tree = SnapshotApi.tree(SnapshotApi.parse(PveJson.parseToJsonElement(recorded)))
        // Oldest first, children under their parent (by date), "Now" last on its branch.
        assertEquals(
            listOf("before_upgrade" to 0, "experiment" to 1, "after_upgrade" to 1, "current" to 2),
            tree.map { it.snapshot.name to it.depth },
        )
    }

    @Test
    fun treeWithMissingParentsAndCycles() {
        fun s(name: String, parent: String?, time: Long?) = Snapshot(name, "", time, parent, false)
        // A parent that was deleted outside Proxmox's normal flow: shown as a root, not hidden.
        val orphan = SnapshotApi.tree(listOf(s("b", "gone", 2), s("a", null, 1), s(Snapshot.CURRENT, "b", null)))
        assertEquals(listOf("a" to 0, "b" to 0, "current" to 1), orphan.map { it.snapshot.name to it.depth })
        // A cycle never loops forever and every snapshot is shown once.
        val cycle = SnapshotApi.tree(listOf(s("x", "y", 1), s("y", "x", 2)))
        assertEquals(setOf("x", "y"), cycle.map { it.snapshot.name }.toSet())
        assertEquals(2, cycle.size)
        // No snapshots at all: only "current".
        assertEquals(listOf("current" to 0), SnapshotApi.tree(listOf(s(Snapshot.CURRENT, null, null))).map { it.snapshot.name to it.depth })
        assertTrue(SnapshotApi.tree(emptyList()).isEmpty())
    }

    @Test
    fun requestPathsAndBodies() {
        val vm = GuestRef("pve", GuestType.QEMU, 101)
        val ct = GuestRef("pve2", GuestType.LXC, 200)
        assertEquals("/nodes/pve/qemu/101/snapshot", SnapshotApi.listPath(vm))
        assertEquals("/nodes/pve2/lxc/200/snapshot", SnapshotApi.listPath(ct))
        assertEquals(
            mapOf("snapname" to "before_upgrade", "description" to "Before apt upgrade", "vmstate" to "1"),
            SnapshotApi.takeParams(vm, "before_upgrade", "  Before apt upgrade \n", withRam = true),
        )
        // No RAM for containers, and no empty description.
        assertEquals(mapOf("snapname" to "s1"), SnapshotApi.takeParams(ct, "s1", "   ", withRam = true))
        assertEquals(mapOf("snapname" to "s1"), SnapshotApi.takeParams(vm, "s1", "", withRam = false))
        assertEquals("/nodes/pve/qemu/101/snapshot/before_upgrade/rollback", SnapshotApi.rollbackPath(vm, "before_upgrade"))
        assertEquals(mapOf("start" to "1"), SnapshotApi.rollbackParams(true))
        assertEquals(emptyMap<String, String>(), SnapshotApi.rollbackParams(false))
        assertEquals("/nodes/pve2/lxc/200/snapshot/s1", SnapshotApi.deletePath(ct, "s1"))
    }
}
