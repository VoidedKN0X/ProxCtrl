package com.vknxworks.proxctrl.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Encryption at rest: the sealed file format, the loss-proof move from an ordinary file, the data key's rules and the sealed cache. */
class SealedFileTest {
    private val key = ByteArray(32) { (it * 7 + 1).toByte() }
    private val json = "{\"updatedAt\":1,\"guests\":[]}".toByteArray()

    private fun tempDir(): File = Files.createTempDirectory("proxctrl-test").toFile()

    /** Stands in for the Android Keystore: a wrap only its own [secret] can undo. */
    private class FakeWrapper(var secret: Byte = 7, var broken: Boolean = false) : KeyWrapper {
        override fun wrap(plain: ByteArray): ByteArray {
            if (broken) throw IllegalStateException("keystore unavailable")
            return byteArrayOf(secret) + plain.map { (it + secret).toByte() }
        }

        override fun unwrap(wrapped: ByteArray): ByteArray {
            if (broken) throw IllegalStateException("keystore unavailable")
            if (wrapped[0] != secret) throw IllegalStateException("wrong key")
            return wrapped.drop(1).map { (it - secret).toByte() }.toByteArray()
        }
    }

    private fun cache(dir: File, wrapper: KeyWrapper) = SealedCache { DataKeyStore(dir, wrapper) }

    @Test
    fun cacheWritesSealedAndReadsBothKinds() {
        val keyDir = tempDir()
        val dir = tempDir()
        val c = cache(keyDir, FakeWrapper())
        val file = File(dir, "overview-a.json")
        assertNull(c.readText(file))
        c.writeText(file, "{\"a\":1}")
        assertTrue(SealedFile.isSealed(file))
        assertFalse(File(dir, "overview-a.json.tmp").exists())
        assertEquals("{\"a\":1}", c.readText(file))
        // A new run with the same key file reads it too.
        assertEquals("{\"a\":1}", cache(keyDir, FakeWrapper()).readText(file))
        // An ordinary file from before encryption is read as it is.
        val old = File(dir, "guests-a.json").apply { writeText("{\"b\":2}") }
        assertEquals("{\"b\":2}", c.readText(old))
        assertFalse(SealedFile.isSealed(old))
    }

    @Test
    fun cacheWithoutAKeystoreKeepsOrdinaryFiles() {
        val dir = tempDir()
        val c = cache(tempDir(), FakeWrapper(broken = true))
        val file = File(dir, "overview-a.json")
        c.writeText(file, "{\"a\":1}")
        assertEquals("{\"a\":1}", file.readText())
        assertEquals("{\"a\":1}", c.readText(file))
        c.sealAll(listOf(dir))
        assertEquals("{\"a\":1}", file.readText())
    }

    @Test
    fun aSealedCacheThatCantBeOpenedIsAMissAndIsRemoved() {
        val keyDir = tempDir()
        val dir = tempDir()
        val file = File(dir, "overview-a.json")
        cache(keyDir, FakeWrapper()).writeText(file, "{\"a\":1}")
        val keyFile = File(keyDir, DataKeyStore.FILE).readBytes()
        // The Keystore key is gone: nothing to show, the file goes, the key file stays as it was.
        val lost = cache(keyDir, FakeWrapper(secret = 9))
        assertNull(lost.readText(file))
        assertFalse(file.exists())
        assertArrayEquals(keyFile, File(keyDir, DataKeyStore.FILE).readBytes())
        // From then on that run saves ordinary files, which can be read again.
        lost.writeText(file, "{\"a\":2}")
        assertEquals("{\"a\":2}", file.readText())
        assertEquals("{\"a\":2}", lost.readText(file))
        // A damaged sealed file is a miss too.
        val good = cache(keyDir, FakeWrapper())
        good.writeText(file, "{\"a\":3}")
        file.writeBytes(file.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
        assertNull(good.readText(file))
        assertFalse(file.exists())
    }

    @Test
    fun sealAllSealsOrdinaryJsonFilesAndClearsLeftovers() {
        val keyDir = tempDir()
        val dir = tempDir()
        val c = cache(keyDir, FakeWrapper())
        val a = File(dir, "guests-a.json").apply { writeText("{\"a\":1}") }
        val b = File(dir, "overview-a.json")
        c.writeText(b, "{\"b\":2}")
        val sealedBefore = b.readBytes()
        val leftover = File(dir, "guests-b.json.tmp").apply { writeText("half") }
        val other = File(dir, "notes.txt").apply { writeText("x") }
        c.sealAll(listOf(dir, File(dir, "missing")))
        assertTrue(SealedFile.isSealed(a))
        assertEquals("{\"a\":1}", c.readText(a))
        assertArrayEquals(sealedBefore, b.readBytes())
        assertFalse(leftover.exists())
        assertEquals("x", other.readText())
        c.delete(a)
        assertFalse(a.exists())
    }

    @Test
    fun sealedBytesOpenAgainAndAreRecognised() {
        val sealed = SealedFile.seal(key, json)
        assertTrue(SealedFile.isSealed(sealed))
        assertFalse(SealedFile.isSealed(json))
        assertFalse(SealedFile.isSealed(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertArrayEquals(json, SealedFile.open(key, sealed))
        assertArrayEquals(ByteArray(0), SealedFile.open(key, SealedFile.seal(key, ByteArray(0))))
    }

    @Test
    fun wrongKeyOrDamageIsRefused() {
        val sealed = SealedFile.seal(key, json)
        assertTrue(runCatching { SealedFile.open(ByteArray(32), sealed) }.exceptionOrNull() is SealedDataException)
        val damaged = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertTrue(runCatching { SealedFile.open(key, damaged) }.exceptionOrNull() is SealedDataException)
        assertTrue(runCatching { SealedFile.open(key, json) }.exceptionOrNull() is SealedDataException)
    }

    @Test
    fun sealInPlaceReplacesTheOrdinaryFileWithACheckedCopy() {
        val dir = tempDir()
        val file = File(dir, "data.json").apply { writeBytes(json) }
        val tmp = File(dir, "data.json.tmp")
        assertTrue(SealedFile.sealInPlace(key, file, tmp))
        assertTrue(SealedFile.isSealed(file))
        assertFalse(tmp.exists())
        assertArrayEquals(json, SealedFile.open(key, file.readBytes()))
        // Already sealed: nothing to do.
        val before = file.readBytes()
        assertTrue(SealedFile.sealInPlace(key, file, tmp))
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun aFailedSealLeavesTheOrdinaryFileAsItWas() {
        val dir = tempDir()
        val file = File(dir, "cache.json").apply { writeBytes(json) }
        // The temporary file can't be written (it's a folder with something in it).
        val blocked = File(dir, "cache.json.seal.tmp").apply { mkdirs(); File(this, "x").writeText("x") }
        assertFalse(SealedFile.sealInPlace(key, file, blocked))
        assertArrayEquals(json, file.readBytes())
        // A key of the wrong size can't seal anything.
        val tmp = File(dir, "t")
        assertFalse(SealedFile.sealInPlace(ByteArray(5), file, tmp))
        assertArrayEquals(json, file.readBytes())
        assertFalse(tmp.exists())
        // A file that isn't there.
        assertFalse(SealedFile.sealInPlace(key, File(dir, "missing"), tmp))
        assertFalse(File(dir, "missing").exists())
    }

    @Test
    fun dataKeyIsMadeOnceAndReadBack() {
        val dir = tempDir()
        val wrapper = FakeWrapper()
        val store = DataKeyStore(dir, wrapper)
        assertNull(store.get(create = false))
        val made = store.get(create = true)
        assertNotNull(made)
        assertArrayEquals(made, DataKeyStore(dir, wrapper).get(create = false))
    }

    @Test
    fun aKeyFileThatCantBeUnwrappedIsNeverReplaced() {
        val dir = tempDir()
        val made = DataKeyStore(dir, FakeWrapper()).get(create = true)
        val onDisk = File(dir, DataKeyStore.FILE).readBytes()
        // The Keystore key is gone (a different one answers): no key, and the key file stays.
        assertNull(DataKeyStore(dir, FakeWrapper(secret = 9)).get(create = true))
        assertArrayEquals(onDisk, File(dir, DataKeyStore.FILE).readBytes())
        assertArrayEquals(made, DataKeyStore(dir, FakeWrapper()).get(create = false))
    }

    @Test
    fun withoutAWorkingKeystoreThereIsNoKeyAndNoKeyFile() {
        val dir = tempDir()
        val wrapper = FakeWrapper(broken = true)
        val store = DataKeyStore(dir, wrapper)
        assertNull(store.get(create = true))
        assertFalse(File(dir, DataKeyStore.FILE).exists())
        // Not asked again in the same run until reset.
        wrapper.broken = false
        assertNull(store.get(create = true))
        store.reset()
        assertNotNull(store.get(create = true))
    }

    @Test
    fun discardSetsTheOldKeyFileAsideAndANewKeyIsMade() {
        val dir = tempDir()
        val store = DataKeyStore(dir, FakeWrapper())
        val first = store.get(create = true)!!
        store.discard(keep = true)
        assertFalse(File(dir, DataKeyStore.FILE).exists())
        assertTrue(File(dir, DataKeyStore.FILE + ".unreadable").exists())
        assertFalse(first.contentEquals(store.get(create = true)!!))
    }
}
