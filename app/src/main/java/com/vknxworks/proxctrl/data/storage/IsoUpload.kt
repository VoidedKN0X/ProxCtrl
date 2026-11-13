package com.vknxworks.proxctrl.data.storage

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import com.vknxworks.proxctrl.data.api.PveClient
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException

/** A file picked on the phone or shared to ProxCtrl. */
data class PickedFile(val uri: Uri, val name: String, val size: Long)

/**
 * ISO uploads: POST /nodes/{node}/storage/{storage}/upload with content=iso, the file name, and the
 * file itself, as the web UI sends them. Needs Datastore.AllocateTemplate on the storage. Proxmox
 * only takes names ending in .iso or .img, and returns a task that moves the file into place.
 */
object IsoUpload {
    private val ISO_NAME = Regex("""^[^/]+\.(iso|img)$""", RegexOption.IGNORE_CASE)

    fun isIsoName(name: String): Boolean = ISO_NAME.matches(name.trim())

    /**
     * Whether a file shared from another app is safe to read: only content:// from another app's
     * provider, never file:// and never a provider that belongs to ProxCtrl itself.
     */
    fun isForeignContentUri(context: Context, uri: Uri): Boolean {
        if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) return false
        // "content://0@<authority>" still reaches <authority> (the number is a user id), so look past it.
        val authority = uri.authority?.substringAfterLast('@')?.takeIf { it.isNotBlank() } ?: return false
        if (authority.startsWith(context.packageName)) return false
        // Whatever its name, a provider that belongs to this app is never "another app's".
        val owner = runCatching {
            context.packageManager.resolveContentProvider(authority, PackageManager.ComponentInfoFlags.of(0))?.packageName
        }.getOrNull()
        return owner != context.packageName
    }

    /** Name and size of a content Uri (size is -1 when the provider doesn't say). */
    fun describe(resolver: ContentResolver, uri: Uri): PickedFile {
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        }
        return PickedFile(uri, name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "upload.iso", size)
    }

    /**
     * Uploads [file] as [fileName] and returns the task UPID Proxmox starts afterwards (or null).
     * [onProgress] gets bytes sent and the total (-1 when unknown), from OkHttp's thread.
     */
    suspend fun upload(
        client: PveClient,
        resolver: ContentResolver,
        node: String,
        storage: String,
        file: PickedFile,
        fileName: String,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): String? {
        val part = UriRequestBody(resolver, file.uri, file.size, "application/octet-stream".toMediaType(), onProgress)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("content", "iso")
            .addFormDataPart("filename", fileName.trim(), part)
            .build()
        val result = client.postMultipart("/nodes/$node/storage/$storage/upload", body)
        return (result as? JsonPrimitive)?.content?.takeIf { it.startsWith("UPID:") }
    }

    /** Streams a content Uri into the request without loading it into memory, reporting progress. */
    private class UriRequestBody(
        private val resolver: ContentResolver,
        private val uri: Uri,
        private val length: Long,
        private val type: MediaType,
        private val onProgress: (Long, Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType = type
        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            val input = resolver.openInputStream(uri) ?: throw IOException("Can't open the file")
            input.source().use { source ->
                var sent = 0L
                var lastReport = 0L
                val buffer = okio.Buffer()
                while (true) {
                    val read = source.read(buffer, 256 * 1024L)
                    if (read == -1L) break
                    sink.write(buffer, read)
                    sent += read
                    if (sent - lastReport >= 512 * 1024L) {
                        lastReport = sent
                        onProgress(sent, length)
                    }
                }
                onProgress(sent, length)
            }
        }
    }
}
