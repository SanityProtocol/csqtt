// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//
// What went wrong on the way to a VPS, as a kind the app words for people and the zoo asserts on (M-141, deploy-environments
// rule 12: every failure names its cause and the next step). The engine keeps the kind and a technical detail; the words are
// the app's (P3), so a translation never touches the engine.
package com.csqtt.deploy.ssh

import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class SshFailure(val retryable: Boolean) {
    /** The host name does not resolve. */
    DNS(true),

    /** Nothing listens on the SSH port (or a firewall rejects it). */
    REFUSED(true),

    /** No route to the host. */
    NO_ROUTE(true),

    /** The connection or a step of it took longer than its deadline. */
    TIMEOUT(true),

    /** The port answered but closed before speaking SSH: another service, or fail2ban / MaxStartups dropping us. */
    NOT_SSH(true),

    /** No key-exchange algorithm in common. */
    KEX(false),

    /** The server's host key is not the pinned one: the user's question (HostKeyChanged), never retried. */
    HOST_KEY_CHANGED(false),

    /** The server refused the login. Never retried: a retry feeds fail2ban (APP-PLAN §4.6). */
    AUTH(false),

    /** The key could not be read: not a private key, or a format sshj does not know. */
    KEY_FORMAT(false),

    /** The key is encrypted and the passphrase is missing or wrong. */
    KEY_PASSPHRASE(false),

    /** The connection dropped after it was up. */
    CLOSED(true),

    /** Anything else; the detail carries what happened. */
    OTHER(false),
}

class SshError(val kind: SshFailure, val detail: String, cause: Throwable? = null) : IOException("$kind: $detail", cause)

object SshFailures {
    /** The kind of [error], from the error or the first cause in its chain that says more. */
    fun classify(error: Throwable): SshFailure {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            direct(current)?.let { return it }
            current = current.cause
            depth++
        }
        return SshFailure.OTHER
    }

    private fun direct(error: Throwable): SshFailure? {
        val message = error.message.orEmpty().lowercase()
        return when {
            error is SshError -> error.kind
            error is HostKeyChanged -> SshFailure.HOST_KEY_CHANGED
            error is UserAuthException -> SshFailure.AUTH
            error is UnknownHostException -> SshFailure.DNS
            error is NoRouteToHostException -> SshFailure.NO_ROUTE
            error is ConnectException -> SshFailure.REFUSED
            error is SocketTimeoutException -> SshFailure.TIMEOUT
            // sshj names this class in a later package in some versions; the name is enough
            error.javaClass.simpleName == "KeyDecryptionFailedException" -> SshFailure.KEY_PASSPHRASE
            error is TransportException && error.disconnectReason == DisconnectReason.HOST_KEY_NOT_VERIFIABLE ->
                SshFailure.HOST_KEY_CHANGED
            error is TransportException && error.disconnectReason == DisconnectReason.KEY_EXCHANGE_FAILED -> SshFailure.KEX
            // the keepalive's verdict on a link that stopped answering (M-140's own case; review F4)
            error is SSHException && error.disconnectReason == DisconnectReason.CONNECTION_LOST -> SshFailure.CLOSED
            "timeout expired" in message || "timed out" in message -> SshFailure.TIMEOUT
            "identification" in message -> SshFailure.NOT_SSH
            "unable to reach a settlement" in message -> SshFailure.KEX
            "broken transport" in message || "connection reset" in message || "end of stream" in message ||
                "broken pipe" in message || "socket closed" in message || "connection abort" in message ||
                "not connected" in message -> SshFailure.CLOSED
            else -> null
        }
    }
}
