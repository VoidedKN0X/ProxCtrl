package com.vknxworks.proxctrl.data.guests

import com.vknxworks.proxctrl.data.api.Permissions
import com.vknxworks.proxctrl.data.api.PveClient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a command run through the guest agent gave back: GET {base}/agent/exec-status. */
data class ExecResult(
    val exited: Boolean,
    val exitCode: Int?,
    /** Set when the process was ended by a signal (or, on Windows, an exception code). */
    val signal: Int?,
    val out: String,
    val err: String,
    val outTruncated: Boolean,
    val errTruncated: Boolean,
) {
    companion object {
        fun parse(data: JsonElement?): ExecResult {
            val o = data as? JsonObject ?: JsonObject(emptyMap())
            fun flag(key: String) = o.str(key)?.let { it == "1" || it == "true" } == true
            return ExecResult(
                exited = flag("exited"),
                exitCode = o.long("exitcode")?.toInt(),
                signal = o.long("signal")?.toInt(),
                out = o.str("out-data").orEmpty(),
                err = o.str("err-data").orEmpty(),
                outTruncated = flag("out-truncated"),
                errTruncated = flag("err-truncated"),
            )
        }
    }
}

/**
 * Actions through the QEMU guest agent of a running VM (qemu-server, PVE::API2::Qemu::Agent):
 * - GET  {base}/agent/info: the agent's version (VM.GuestAgent.Audit).
 * - POST {base}/agent/fsfreeze-status, fsfreeze-freeze, fsfreeze-thaw (VM.GuestAgent.FileSystemMgmt).
 * - POST {base}/agent/shutdown: a clean shutdown asked of the guest's own system (VM.PowerMgmt).
 * - POST {base}/agent/exec with the command as a list, then GET {base}/agent/exec-status?pid=
 *   (VM.GuestAgent.Unrestricted).
 * Proxmox VE 8 has one privilege for all of these instead, VM.Monitor.
 */
object AgentApi {
    const val PRIV_FS = "VM.GuestAgent.FileSystemMgmt"
    const val PRIV_ALL = "VM.GuestAgent.Unrestricted"
    const val PRIV_POWER = "VM.PowerMgmt"

    /** Proxmox VE 8: the one privilege that covered the guest agent. */
    const val PRIV_LEGACY = "VM.Monitor"

    fun canFreeze(p: Permissions, acl: String): Boolean = p.has(acl, PRIV_FS) || p.has(acl, PRIV_ALL) || p.has(acl, PRIV_LEGACY)
    fun canExec(p: Permissions, acl: String): Boolean = p.has(acl, PRIV_ALL) || p.has(acl, PRIV_LEGACY)
    fun canShutdown(p: Permissions, acl: String): Boolean = p.has(acl, PRIV_POWER) || p.has(acl, PRIV_ALL)

    private fun result(data: JsonElement?): JsonElement? = (data as? JsonObject)?.get("result")

    /** The agent's version, which also shows that it answers. */
    suspend fun version(client: PveClient, ref: GuestRef): String? =
        (result(client.get("${ref.base}/agent/info")) as? JsonObject)?.str("version")

    /** "thawed" or "frozen". */
    suspend fun freezeStatus(client: PveClient, ref: GuestRef): String? =
        (result(client.post("${ref.base}/agent/fsfreeze-status")) as? JsonPrimitive)?.content

    /** Freezes the guest's file systems; returns how many were frozen. */
    suspend fun freeze(client: PveClient, ref: GuestRef): Long? =
        (result(client.post("${ref.base}/agent/fsfreeze-freeze")) as? JsonPrimitive)?.content?.toLongOrNull()

    suspend fun thaw(client: PveClient, ref: GuestRef): Long? =
        (result(client.post("${ref.base}/agent/fsfreeze-thaw")) as? JsonPrimitive)?.content?.toLongOrNull()

    suspend fun shutdown(client: PveClient, ref: GuestRef) {
        client.post("${ref.base}/agent/shutdown")
    }

    /** Starts [argv] (program, then its arguments) in the guest; returns the process ID to ask about. */
    suspend fun exec(client: PveClient, ref: GuestRef, argv: List<String>): Long? =
        (client.postForm("${ref.base}/agent/exec", argv.map { "command" to it }) as? JsonObject)?.long("pid")

    suspend fun execStatus(client: PveClient, ref: GuestRef, pid: Long): ExecResult =
        ExecResult.parse(client.get("${ref.base}/agent/exec-status", mapOf("pid" to pid.toString())))

    /**
     * Splits a typed command line into the program and its arguments, the way the agent wants them:
     * by spaces, with "double" or 'single' quotes keeping spaces together. Inside double quotes \" is
     * a quote character; a backslash is otherwise kept as typed, so Windows paths work. There is no
     * shell in between, so pipes, redirects and variables aren't interpreted; run a shell explicitly
     * for those (sh -c "…" or cmd /c "…").
     */
    fun splitCommand(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inWord = false
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                quote == '\'' -> if (ch == '\'') quote = null else cur.append(ch)
                quote == '"' -> when {
                    ch == '"' -> quote = null
                    ch == '\\' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                    else -> cur.append(ch)
                }
                ch == '\'' || ch == '"' -> { quote = ch; inWord = true }
                ch.isWhitespace() -> if (inWord) { out += cur.toString(); cur.setLength(0); inWord = false }
                else -> { cur.append(ch); inWord = true }
            }
            i++
        }
        if (inWord) out += cur.toString()
        return out
    }
}
