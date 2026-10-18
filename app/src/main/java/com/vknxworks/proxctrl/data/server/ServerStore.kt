package com.vknxworks.proxctrl.data.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Saved servers and the active one. Server settings are plain JSON; every secret (token secret,
 * password, ticket, CSRF token) is encrypted with an AES-GCM key that lives in the Android Keystore
 * and never leaves it. Nothing here is backed up or moved to another phone (see data_extraction_rules).
 */
class ServerStore(context: Context) {
    private val sp = context.getSharedPreferences("servers", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(Server.serializer())

    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<Server>> = _servers.asStateFlow()

    private val _activeId = MutableStateFlow(sp.getString(KEY_ACTIVE, null)?.takeIf { id -> _servers.value.any { it.id == id } }
        ?: _servers.value.firstOrNull()?.id)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private fun load(): List<Server> = try {
        sp.getString(KEY_SERVERS, null)?.let { json.decodeFromString(listSerializer, it) } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    fun save(server: Server) {
        val list = _servers.value.filterNot { it.id == server.id } + server
        val sorted = list.sortedBy { s -> _servers.value.indexOfFirst { it.id == s.id }.let { if (it < 0) Int.MAX_VALUE else it } }
        sp.edit().putString(KEY_SERVERS, json.encodeToString(listSerializer, sorted)).apply()
        _servers.value = sorted
    }

    @Synchronized
    fun update(id: String, transform: (Server) -> Server) {
        val current = _servers.value.firstOrNull { it.id == id } ?: return
        save(transform(current))
    }

    @Synchronized
    fun remove(id: String) {
        val list = _servers.value.filterNot { it.id == id }
        sp.edit()
            .putString(KEY_SERVERS, json.encodeToString(listSerializer, list))
            .remove(secretKey(id, "token")).remove(secretKey(id, "password"))
            .remove(secretKey(id, "ticket")).remove(secretKey(id, "csrf")).remove(secretKey(id, "ticket_at"))
            .apply()
        _servers.value = list
        if (_activeId.value == id) setActive(list.firstOrNull()?.id)
    }

    fun setActive(id: String?) {
        sp.edit().putString(KEY_ACTIVE, id).apply()
        _activeId.value = id
    }

    fun secrets(id: String): ServerSecrets = ServerSecrets(
        tokenSecret = decrypt(sp.getString(secretKey(id, "token"), null)),
        password = decrypt(sp.getString(secretKey(id, "password"), null)),
        ticket = decrypt(sp.getString(secretKey(id, "ticket"), null)),
        csrf = decrypt(sp.getString(secretKey(id, "csrf"), null)),
        ticketIssuedAt = sp.getLong(secretKey(id, "ticket_at"), 0),
    )

    fun saveSecrets(id: String, s: ServerSecrets) {
        sp.edit()
            .putString(secretKey(id, "token"), encrypt(s.tokenSecret))
            .putString(secretKey(id, "password"), encrypt(s.password))
            .putString(secretKey(id, "ticket"), encrypt(s.ticket))
            .putString(secretKey(id, "csrf"), encrypt(s.csrf))
            .putLong(secretKey(id, "ticket_at"), s.ticketIssuedAt)
            .apply()
    }

    private fun secretKey(id: String, what: String) = "secret_${id}_$what"

    // --- Keystore AES-GCM ---

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun decrypt(stored: String?): String {
        if (stored.isNullOrEmpty()) return ""
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12))
        } catch (_: Exception) {
            ""
        }
    }

    private companion object {
        const val KEY_SERVERS = "servers"
        const val KEY_ACTIVE = "active"
        const val KEY_ALIAS = "proxctrl_secrets"
    }
}
