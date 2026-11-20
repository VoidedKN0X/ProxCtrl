package com.vknxworks.proxctrl.data.crypto

import java.io.File

/**
 * Small files the app can make again (the offline copies of what the server last reported, and the notes
 * the background checks keep), stored sealed ([SealedFile]) under the data key ([DataKeyStore]).
 *
 * - Reading takes both kinds: a sealed file and an ordinary one from before encryption.
 * - Writing seals when there is a data key and writes an ordinary file when there isn't (no working
 *   Keystore), exactly as before encryption.
 * - A sealed file that can't be opened (the key is gone, or the file is damaged) counts as "nothing saved":
 *   it's deleted and the app reads the data from the server again. That is only right because everything
 *   kept here can be made again; never use this for something the user typed in.
 *
 * The data key is asked for outside the file lock, so deleting a file (which can happen on the main thread
 * when a server is removed) never waits for the Keystore. Reading and writing ask the Keystore the first
 * time: call them off the main thread.
 */
class SealedCache(keySource: () -> DataKeyStore) {
    private val keys by lazy(keySource)
    private val lock = Any()

    /** The text in [file], or null when there's nothing to read. */
    fun readText(file: File): String? {
        val bytes = synchronized(lock) {
            try {
                if (file.isFile) file.readBytes() else null
            } catch (e: Exception) {
                null
            }
        } ?: return null
        if (!SealedFile.isSealed(bytes)) return String(bytes, Charsets.UTF_8)
        val key = keys.get(create = false)
        val plain = if (key == null) {
            null
        } else {
            try {
                SealedFile.open(key, bytes)
            } catch (e: SealedDataException) {
                null
            }
        }
        if (plain == null) {
            // Can't be opened and can be made again: out of the way, so the next save starts clean.
            delete(file)
            return null
        }
        return String(plain, Charsets.UTF_8)
    }

    /**
     * Saves [text] in [file], sealed when there is a data key. Written to a temporary file first and then
     * moved in place, so a write that's cut short never leaves half a file. Throws when the file can't be written.
     */
    fun writeText(file: File, text: String) {
        val plain = text.toByteArray(Charsets.UTF_8)
        val key = keys.get(create = true)
        val bytes = (if (key != null) SealedFile.sealChecked(key, plain) else null) ?: plain
        synchronized(lock) {
            val tmp = File(file.parentFile, file.name + TMP)
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    /** Deletes [file] (a removed server's copy). Doesn't touch the Keystore. */
    fun delete(file: File) {
        synchronized(lock) {
            try {
                file.delete()
            } catch (e: Exception) {
            }
        }
    }

    /**
     * Seals every ordinary `.json` file in [dirs] that was saved before encryption, one at a time through
     * [SealedFile.sealInPlace] (sealed copy written and synced, read back, compared, then moved over the
     * original in one step). A file that can't be sealed stays as it is and is tried again at the next start.
     * Temporary files left behind by a save that was cut short are removed. The data key is only asked for
     * when there is something to seal; without one nothing is sealed.
     */
    fun sealAll(dirs: List<File>) {
        var key: ByteArray? = null
        var asked = false
        for (dir in dirs) {
            val names = try {
                dir.list()
            } catch (e: Exception) {
                null
            } ?: continue
            for (name in names) {
                val file = File(dir, name)
                if (name.endsWith(TMP)) {
                    // No save is running while the lock is held, so this is a leftover.
                    synchronized(lock) {
                        try {
                            if (file.isFile) file.delete()
                        } catch (e: Exception) {
                        }
                    }
                    continue
                }
                if (!name.endsWith(".json") || !isOrdinary(file)) continue
                if (!asked) {
                    asked = true
                    key = keys.get(create = true)
                }
                val k = key ?: return
                synchronized(lock) {
                    // Looked at again under the lock: it may have been saved sealed, or removed, in between.
                    if (isOrdinary(file)) SealedFile.sealInPlace(k, file, File(dir, name + TMP))
                }
            }
        }
    }

    private fun isOrdinary(file: File): Boolean = try {
        file.isFile && !SealedFile.isSealed(file)
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val TMP = ".tmp"
    }
}
