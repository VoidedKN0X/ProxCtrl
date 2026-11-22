package com.vknxworks.proxctrl.data.storage

import com.vknxworks.proxctrl.data.api.PveClient
import com.vknxworks.proxctrl.data.server.AuthMethod
import com.vknxworks.proxctrl.data.server.Server
import com.vknxworks.proxctrl.data.server.ServerSecrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Leaving the upload screen cancels the coroutine; that must cancel the HTTP call itself, so the upload
 * stops instead of streaming on in the background. A fake server (an interceptor) holds the request open
 * until it sees the call cancelled.
 */
class UploadCancelTest {

    private val server = Server(
        id = "s1",
        name = "Home",
        url = "https://pve.example.test:8006",
        auth = AuthMethod.TOKEN,
        tokenId = "proxctrl@pve!phone",
    )

    @Test
    fun cancellingTheCoroutineCancelsTheUpload() = runBlocking {
        val started = CountDownLatch(1)
        val sawCancel = CountDownLatch(1)
        val slowServer = Interceptor { chain ->
            started.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!chain.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(10)
            if (chain.call().isCanceled()) {
                sawCancel.countDown()
                throw IOException("Canceled")
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"data":null}""".toResponseBody("application/json".toMediaType()))
                .build()
        }
        val client = PveClient(server, OkHttpClient.Builder().addInterceptor(slowServer).build(), ServerSecrets(tokenSecret = "x"))
        val job = launch(Dispatchers.IO) {
            client.postMultipart("/nodes/pve/storage/local/upload", "iso".toRequestBody("application/octet-stream".toMediaType()))
        }
        assertTrue("upload never started", started.await(5, TimeUnit.SECONDS))
        job.cancel()
        assertTrue("the HTTP call was not cancelled", sawCancel.await(5, TimeUnit.SECONDS))
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test
    fun aFinishedUploadStillReturnsTheAnswer() = runBlocking {
        val upid = "UPID:pve:0001E0F2:02A5C1B3:6734A1B2:imgcopy::root@pam:"
        val ok = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"data":"$upid"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }
        val client = PveClient(server, OkHttpClient.Builder().addInterceptor(ok).build(), ServerSecrets(tokenSecret = "x"))
        val result = client.postMultipart("/nodes/pve/storage/local/upload", "iso".toRequestBody("application/octet-stream".toMediaType()))
        assertEquals("\"$upid\"", result.toString())
    }
}
