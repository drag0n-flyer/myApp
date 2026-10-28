package org.example.app.interop

import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * The Android interop peer (scripts/interop.sh). One command per line on stdin, one answer per line on stdout:
 * `OK [values]` or `ERR <why>`. Logs go to stderr. The command set is the same as the Swift peer's
 * (apps/apple/Interop); `<text>` is the rest of the line.
 *
 *   signup <username> <email> <display name>   -> OK <accountID>
 *   add <username>                             -> OK <accountID>
 *   send <peer> <text>                         -> OK <messageID>
 *   wait-text <peer> <text>                    -> OK <messageID>
 *   reply <peer> <messageID> <text>            -> OK <messageID>
 *   wait-reply <peer> <messageID> <text>       -> OK <messageID>
 *   react <peer> <messageID> <emoji>           -> OK
 *   wait-reaction <peer> <messageID> <emoji>   -> OK
 *   edit <peer> <messageID> <text>             -> OK
 *   wait-edit <peer> <messageID> <text>        -> OK
 *   read <peer>                                -> OK
 *   wait-status <peer> <messageID> delivered|read -> OK
 *   send-photo <peer> <path>                   -> OK <messageID> <sha256 of the bytes sent>
 *   wait-photo <peer> <messageID>              -> OK <sha256 of the decrypted bytes>
 *   conversation <peer>                        -> OK <server conversation ID>
 *   safety <peer>                              -> OK <safety number digits>
 *   member-keys <peer>                         -> OK real|placeholder <count>, or OK none (no real keys)
 *   seal <peer> ring|offer <callID> [<sdp path>] -> OK <base64 sealed>
 *   open <peer> <base64 sealed>                -> OK <kind> <callID> [fingerprint-ok]
 *   link-code                                  -> OK <QR text>   (a fresh device)
 *   link-wait                                  -> OK <accountID> imported <contact IDs, comma separated>
 *   approve <QR text>                          -> OK
 *   quit
 */
fun main() {
    fun env(name: String) = System.getenv(name)?.takeIf { it.isNotEmpty() } ?: run {
        System.err.println("$name is not set; run scripts/interop.sh")
        exitProcess(2)
    }
    val peer = Peer(
        PeerConfig(
            baseUrl = env("INTEROP_BASE_URL"),
            mailDir = File(env("INTEROP_MAIL_DIR")),
            invite = env("INTEROP_INVITE"),
            dataDir = File(env("INTEROP_DATA")),
            deviceName = env("INTEROP_DEVICE"),
        ),
    )
    runBlocking {
        while (true) {
            val line = readlnOrNull() ?: break
            if (line.isBlank()) continue
            if (line.trim() == "quit") break
            val answer = try {
                val values = run(peer, line.trim())
                if (values.isEmpty()) "OK" else "OK $values"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("command failed: ${line.substringBefore(' ')}")
                e.printStackTrace()
                "ERR ${e.javaClass.simpleName}: ${e.message ?: ""}".replace('\n', ' ')
            }
            println(answer)
            System.out.flush()
        }
    }
    exitProcess(0)
}

private suspend fun run(peer: Peer, line: String): String {
    val name = line.substringBefore(' ')
    // args(n): the first n words after the command, then the rest of the line as one value.
    fun args(n: Int): List<String> {
        val parts = line.substringAfter(' ', "").split(' ', limit = n + 1)
        if (parts.size < n + 1 || parts.any { it.isEmpty() }) throw PeerError("usage: $name needs ${n + 1} values")
        return parts
    }
    return when (name) {
        "signup" -> args(2).let { peer.signup(it[0], it[1], it[2]) }
        "add" -> args(0).let { peer.add(it[0]) }
        "send" -> args(1).let { peer.send(it[0], it[1]) }
        "wait-text" -> args(1).let { peer.waitText(it[0], it[1]) }
        "reply" -> args(2).let { peer.send(it[0], it[2], replyTo = it[1]) }
        "wait-reply" -> args(2).let { peer.waitText(it[0], it[2], replyTo = it[1]) }
        "react" -> args(2).let { peer.react(it[0], it[1], it[2]); "" }
        "wait-reaction" -> args(2).let { peer.waitReaction(it[0], it[1], it[2]); "" }
        "edit" -> args(2).let { peer.edit(it[0], it[1], it[2]); "" }
        "wait-edit" -> args(2).let { peer.waitEdit(it[0], it[1], it[2]); "" }
        "read" -> args(0).let { peer.read(it[0]); "" }
        "wait-status" -> args(2).let { peer.waitStatus(it[0], it[1], it[2]); "" }
        "send-photo" -> args(1).let { peer.sendPhoto(it[0], it[1]) }
        "wait-photo" -> args(1).let { peer.waitPhoto(it[0], it[1]) }
        "conversation" -> args(0).let { peer.conversation(it[0]) }
        "member-keys" -> args(0).let { peer.memberKeys(it[0]) }
        "safety" -> args(0).let { peer.safety(it[0]) }
        "seal" -> {
            val parts = line.split(' ')
            if (parts.size !in 4..5) throw PeerError("usage: seal <peer> <kind> <callID> [<sdp path>]")
            peer.seal(parts[1], parts[2], parts[3], parts.getOrNull(4))
        }
        "open" -> args(1).let { peer.open(it[0], it[1]) }
        "link-code" -> peer.linkCode()
        "link-wait" -> peer.linkWait()
        "approve" -> args(0).let { peer.approve(it[0]); "" }
        else -> throw PeerError("unknown command $name")
    }
}
