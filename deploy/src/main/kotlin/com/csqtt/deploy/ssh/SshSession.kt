// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//
// deploy v3's SSH transport (CSQTT-APP-PLAN §6.2 step 1, §6.3; P2 B1). What today's createSSHClient got wrong, and this does:
// - the keepalive is configured BEFORE connect (sshj starts its keepalive thread inside connect only if an interval is set
//   then; today's code sets it after, so a dead link hung for minutes: M-140, DE-T1), and it is the KEEP_ALIVE kind, which
//   expects replies and drops a link that stops answering;
// - connecting and the handshake are bounded (15 s each) and retried once for a transient failure, never for a refused login
//   (fail2ban) or a changed host key (the user's question);
// - every failure path closes the transport: a refused login no longer leaks a connected client;
// - a key is read before any connection, so a wrong key or passphrase never costs a login attempt;
// - certificates work everywhere (M-144): a key and its OpenSSH certificate go together into the public-key login. sshj 0.38
//   reads a certificate given as text only beside a PEM key and drops the one beside an OpenSSH-format key, today's default
//   (so the shipped app's certificate login never offered its certificate); the engine pairs them itself.
package com.csqtt.deploy.ssh

import com.hierynomus.sshj.userauth.certificate.Certificate
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyFileUtil
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.keepalive.KeepAliveRunner
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.StringReader
import java.security.PrivateKey
import java.security.PublicKey
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What one remote command left: its stdout bytes (marker lines live here), its stderr text, and its exit status. */
class ExecResult(val stdout: ByteArray, val stderr: String, val exitStatus: Int?) {
    val stdoutText: String get() = String(stdout, Charsets.UTF_8)
}

data class SshTimeouts(
    val connectMs: Int = 15_000,
    val handshakeMs: Int = 15_000,
    /** Socket reads: the keepalive answers every 10 s, so only a dead link ever waits this long. */
    val socketMs: Int = 60_000,
    val keepAliveSeconds: Int = 10,
    /** Unanswered keepalives before sshj drops the link (~30–40 s for a link that went away). */
    val keepAliveMisses: Int = 3,
)

class SshSession private constructor(private val client: SSHClient) : Closeable {
    val isConnected: Boolean get() = client.isConnected

    /**
     * Runs [command] with [stdin] (written whole, then closed, so a script on stdin ends where its bytes end) and returns
     * when the command exits, or throws SshError(TIMEOUT) after [deadlineMs], the one bound on the whole call, stdin
     * included; a timed-out command's channel is closed. A command that exits before taking all its stdin (dd on a full
     * disk) returns its own status and stderr, which say why, not the write's error; a link lost on the way is an SshError.
     */
    fun exec(command: RemoteCommand, stdin: ByteArray? = null, deadlineMs: Long = 60_000): ExecResult {
        val session = try {
            client.startSession()
        } catch (error: Exception) {
            throw SshError(SshFailures.classify(error), "start a session: ${error.message}", error)
        }
        session.use {
            val cmd = try {
                session.exec(command.toString())
            } catch (error: Exception) {
                throw SshError(SshFailures.classify(error), "exec: ${error.message}", error)
            }
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val readers = listOf(drain(cmd.inputStream, stdout), drain(cmd.errorStream, stderr))
            // from a thread of its own: a remote that stops reading (a hung disk under dd) would block a write with no
            // bound of its own, so only the deadline below ends it, by closing the channel under it
            var writeError: Throwable? = null
            val writer = thread(isDaemon = true, name = "ssh-stdin") {
                writeError = runCatching { cmd.outputStream.use { input -> stdin?.let { input.write(it) } } }.exceptionOrNull()
            }
            try {
                cmd.join(deadlineMs, TimeUnit.MILLISECONDS)
            } catch (error: Exception) {
                runCatching { cmd.close() }
                throw SshError(SshFailures.classify(error), "\"$command\": ${error.message}", error)
            }
            val status = cmd.exitStatus
            if (status == null && cmd.isOpen) {
                runCatching { cmd.close() }
                (readers + writer).forEach { it.join(1_000) }
                throw SshError(SshFailure.TIMEOUT, "\"$command\" did not finish in ${deadlineMs / 1000} s")
            }
            writer.join(5_000)
            readers.forEach { it.join(5_000) }
            if (status == null) {
                // the channel closed with no exit status (a signal, or a lost link): the write's error, if any, says more
                writeError?.let { throw SshError(SshFailures.classify(it), "write the command's stdin: ${it.message}", it) }
            }
            return ExecResult(stdout.toByteArray(), String(stderr.toByteArray(), Charsets.UTF_8), status)
        }
    }

    override fun close() {
        runCatching { client.disconnect() }
        runCatching { client.close() }
    }

    companion object {
        /** Before the one retry of a transient failure: a provider's brief refusal is often over a second later. */
        private const val RETRY_PAUSE_MS = 1_500L

        /**
         * Connects to [target], checks its host key against [pins] (TOFU), and signs in. Throws [HostKeyChanged] for a new
         * key, SshError for everything else; nothing is left open on a failure.
         */
        fun open(target: SshTarget, pins: HostKeyPins, timeouts: SshTimeouts = SshTimeouts()): SshSession {
            val keys = (target.auth as? SshAuth.Key)?.let { loadKey(it) }
            var lastError: Exception? = null
            for (attempt in 1..2) {
                val verifier = TofuVerifier(pins)
                val client = newClient(timeouts)
                client.addHostKeyVerifier(verifier)
                try {
                    client.connect(target.host, target.port)
                } catch (error: Exception) {
                    runCatching { client.close() }
                    verifier.change?.let { throw it }
                    val kind = SshFailures.classify(error)
                    val failure = SshError(kind, "connect to ${target.host}:${target.port}: ${error.message}", error)
                    lastError = failure
                    if (kind.retryable && attempt == 1) {
                        Thread.sleep(RETRY_PAUSE_MS)
                        continue
                    }
                    throw failure
                }
                try {
                    signIn(client, target, keys)
                    return SshSession(client)
                } catch (error: Exception) {
                    runCatching { client.disconnect() }
                    runCatching { client.close() }
                    val kind = SshFailures.classify(error)
                    // a refused login is never retried; a link that dropped during the handshake is, once
                    val failure = SshError(kind, "sign in as ${target.user}: ${error.message}", error)
                    lastError = failure
                    if (kind.retryable && attempt == 1) {
                        Thread.sleep(RETRY_PAUSE_MS)
                        continue
                    }
                    throw failure
                }
            }
            throw lastError ?: SshError(SshFailure.OTHER, "no attempt made")
        }

        private fun newClient(timeouts: SshTimeouts): SSHClient {
            val config = DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
            return SSHClient(config).apply {
                connectTimeout = timeouts.connectMs
                timeout = timeouts.socketMs
                transport.timeoutMs = timeouts.handshakeMs
                // before connect: sshj starts the keepalive thread in connect, and only if an interval is set by then
                connection.keepAlive.keepAliveInterval = timeouts.keepAliveSeconds
                (connection.keepAlive as? KeepAliveRunner)?.setMaxAliveCount(timeouts.keepAliveMisses)
            }
        }

        private fun signIn(client: SSHClient, target: SshTarget, keys: KeyProvider?) {
            when (val auth = target.auth) {
                is SshAuth.Key -> client.authPublickey(target.user, requireNotNull(keys) { "the key was not loaded" })
                is SshAuth.Password -> {
                    // password, then keyboard-interactive with the same password for servers that allow only that
                    val finder = PasswordUtils.createOneOff(auth.password.toCharArray())
                    val interactive = PasswordUtils.createOneOff(auth.password.toCharArray())
                    client.auth(target.user, AuthPassword(finder), AuthKeyboardInteractive(PasswordResponseProvider(interactive)))
                }
            }
        }

        /** Parses the key (and its certificate) now, with no network: SshError(KEY_FORMAT or KEY_PASSPHRASE) otherwise. */
        fun loadKey(key: SshAuth.Key): KeyProvider {
            val passphrase = key.passphrase?.takeIf { it.isNotEmpty() }
            val finder: PasswordFinder? = passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
            val provider = try {
                SSHClient(DefaultConfig()).loadKeys(key.privateKey, null, finder)
            } catch (error: Exception) {
                throw keyError(error, passphrase != null)
            }
            try {
                // sshj reads a key lazily; read it now so a bad key fails here, not at the server
                check(provider.public != null && provider.private != null) { "the key file holds no key" }
            } catch (error: Exception) {
                throw keyError(error, passphrase != null)
            }
            val certificate = key.certificate?.takeIf { it.isNotBlank() } ?: return provider
            return withCertificate(provider, certificate)
        }

        /** The key's login offers [certificate] in place of its plain public key; a certificate of another key is refused. */
        private fun withCertificate(key: KeyProvider, certificate: String): KeyProvider {
            val parsed = try {
                OpenSSHKeyFileUtil.initPubKey(StringReader(certificate.trim() + "\n"))
            } catch (error: Exception) {
                throw SshError(SshFailure.KEY_FORMAT, "read the certificate: ${error.message}", error)
            }
            val cert = parsed.pubKey as? Certificate<*>
                ?: throw SshError(SshFailure.KEY_FORMAT, "the certificate file holds a plain public key, not a certificate")
            val ownKey = key.public
            if (HostKeys.fingerprint(cert.key) != HostKeys.fingerprint(ownKey)) {
                throw SshError(SshFailure.KEY_FORMAT, "the certificate is for another key")
            }
            val private = key.private
            return object : KeyProvider {
                override fun getPrivate(): PrivateKey = private
                override fun getPublic(): PublicKey = cert
                override fun getType(): KeyType = parsed.type
            }
        }

        private fun keyError(error: Exception, hadPassphrase: Boolean): SshError {
            val message = error.message.orEmpty().lowercase()
            val passphraseProblem = SshFailures.classify(error) == SshFailure.KEY_PASSPHRASE ||
                "passphrase" in message || "password" in message || "decrypt" in message || "encrypted" in message
            val kind = if (passphraseProblem) SshFailure.KEY_PASSPHRASE else SshFailure.KEY_FORMAT
            val what = if (kind == SshFailure.KEY_PASSPHRASE && !hadPassphrase) "the key is encrypted and no passphrase was given"
            else "read the key: ${error.message}"
            return SshError(kind, what, error)
        }

        private fun drain(input: InputStream, into: ByteArrayOutputStream): Thread =
            thread(isDaemon = true, name = "ssh-drain") {
                runCatching { input.copyTo(into) }
            }
    }
}

