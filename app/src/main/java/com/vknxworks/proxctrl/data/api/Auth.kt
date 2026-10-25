package com.vknxworks.proxctrl.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Sign-in calls that work before a server is saved: the realm list (no login needed) and
 * POST /access/ticket, including the two-factor step.
 */
object Auth {

    /** Login realms (pam, pve, LDAP, …). Needs no login, so it also tells us the address is a Proxmox API. */
    suspend fun realms(http: OkHttpClient, baseUrl: String): List<Realm> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/api2/json/access/domains".toHttpUrl()).get().build()
        val data = try {
            http.newCall(request).execute().use { PveClient.parse(it) }
        } catch (e: IOException) {
            throw PveError.fromIo(e)
        }
        runCatching { PveJson.decodeFromJsonElement(ListSerializer(Realm.serializer()), data) }.getOrElse { throw PveError.NotProxmox(null) }
            .filter { it.realm.isNotBlank() }
    }

    /**
     * POST /access/ticket. [password] may also be a still-valid ticket (renewal). With a two-factor
     * account the answer has `NeedTFA = 1` and its ticket is the challenge for [finishTfa].
     */
    suspend fun requestTicket(http: OkHttpClient, baseUrl: String, username: String, password: String): TicketResponse =
        postTicket(http, baseUrl, mapOf("username" to username, "password" to password))

    /**
     * Answers a two-factor challenge. [response] is "totp:123456" for an authenticator code or
     * "recovery:KEY" for a recovery key, as the Proxmox web UI sends them.
     */
    suspend fun finishTfa(http: OkHttpClient, baseUrl: String, username: String, challenge: String, response: String): TicketResponse =
        postTicket(http, baseUrl, mapOf("username" to username, "tfa-challenge" to challenge, "password" to response))

    private suspend fun postTicket(http: OkHttpClient, baseUrl: String, params: Map<String, String>): TicketResponse =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
            val request = Request.Builder().url("$baseUrl/api2/json/access/ticket".toHttpUrl()).post(body).build()
            val data = try {
                http.newCall(request).execute().use { PveClient.parse(it) }
            } catch (e: IOException) {
                throw PveError.fromIo(e)
            }
            // SerializationException is an IllegalArgumentException: an odd answer is an error, not a crash.
            try {
                PveJson.decodeFromJsonElement(TicketResponse.serializer(), data)
            } catch (e: IllegalArgumentException) {
                throw PveError.NotProxmox(null)
            }
        }

    fun totp(code: String) = "totp:${code.filter { it.isDigit() }}"
    fun recovery(key: String) = "recovery:${key.trim()}"
}
