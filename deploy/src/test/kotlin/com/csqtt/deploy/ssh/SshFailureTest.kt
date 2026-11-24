// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.ssh

import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SshFailureTest {
    @Test
    fun eachFailureGetsItsKind() {
        val cases = mapOf(
            UnknownHostException("vps.example") to SshFailure.DNS,
            ConnectException("Connection refused") to SshFailure.REFUSED,
            NoRouteToHostException("No route to host") to SshFailure.NO_ROUTE,
            SocketTimeoutException("connect timed out") to SshFailure.TIMEOUT,
            UserAuthException("Exhausted available authentication methods") to SshFailure.AUTH,
            TransportException(DisconnectReason.HOST_KEY_NOT_VERIFIABLE, "Could not verify `ssh-ed25519` host key")
                to SshFailure.HOST_KEY_CHANGED,
            TransportException(DisconnectReason.KEY_EXCHANGE_FAILED, "no common kex") to SshFailure.KEX,
            TransportException("Timeout expired: 15000 MILLISECONDS") to SshFailure.TIMEOUT,
            TransportException("Server closed connection during identification exchange") to SshFailure.NOT_SSH,
            TransportException("Unable to reach a settlement: [a] and [b]") to SshFailure.KEX,
            IOException("Broken transport; encountered EOF") to SshFailure.CLOSED,
            // review F4: the keepalive's verdict on a link that stopped answering, and a network switch's words
            net.schmizz.sshj.connection.ConnectionException(DisconnectReason.CONNECTION_LOST,
                "Did not receive any keep-alive response for 30 seconds") to SshFailure.CLOSED,
            java.net.SocketException("Broken pipe") to SshFailure.CLOSED,
            java.net.SocketException("Software caused connection abort") to SshFailure.CLOSED,
            java.net.SocketException("Socket closed") to SshFailure.CLOSED,
            // a session a timeout or a lost link ended (verify N1)
            IllegalStateException("Not connected") to SshFailure.CLOSED,
            HostKeyChanged("vps", 22, "SHA256:a", "SHA256:b") to SshFailure.HOST_KEY_CHANGED,
            IllegalStateException("something new") to SshFailure.OTHER,
        )
        for ((error, kind) in cases) assertEquals(error.toString(), kind, SshFailures.classify(error))
    }

    @Test
    fun aKindFoundDeeperInTheCauseChainCounts() {
        val wrapped = IOException("could not connect", RuntimeException("while connecting", ConnectException("refused")))
        assertEquals(SshFailure.REFUSED, SshFailures.classify(wrapped))
        assertEquals(SshFailure.AUTH, SshFailures.classify(SshError(SshFailure.AUTH, "refused", UserAuthException("x"))))
    }

    @Test
    fun noRetryCanFeedFail2banOrAnswerTheUsersQuestion() {
        // a refused login, a changed host key and a bad key are never tried again (APP-PLAN §4.6, D1)
        for (kind in listOf(SshFailure.AUTH, SshFailure.HOST_KEY_CHANGED, SshFailure.KEY_FORMAT, SshFailure.KEY_PASSPHRASE)) {
            assertFalse(kind.name, kind.retryable)
        }
        for (kind in listOf(SshFailure.TIMEOUT, SshFailure.REFUSED, SshFailure.NOT_SSH, SshFailure.CLOSED)) {
            assertTrue(kind.name, kind.retryable)
        }
    }
}
