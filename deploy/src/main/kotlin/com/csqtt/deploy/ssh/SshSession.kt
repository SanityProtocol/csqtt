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

import com.hierynomus.sshj.key.KeyAlgorithms
import com.hierynomus.sshj.userauth.certificate.Certificate
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyFileUtil
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.keepalive.KeepAliveRunner
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.signature.SignatureRSA
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** What one remote command left: its stdout bytes (marker lines live here), its stderr text, and its exit status. */
class ExecResult(val stdout: ByteArray, val stderr: String, val exitStatus: Int?) {
    val stdoutText: String get() = String(stdout, Charsets.UTF_8)
}

data class SshTimeouts(
    val connectMs: Int = 15_000,
    /** The server's banner, the key exchange and the login (a port that accepts and stays silent waits this long). */
    val handshakeMs: Int = 15_000,
    val keepAliveSeconds: Int = 10,
    /** Unanswered keepalives before sshj drops the link (~30–40 s for a link that went away). */
    val keepAliveMisses: Int = 3,
)

class SshSession private constructor(private val client: SSHClient) : Closeable {
    val isConnected: Boolean get() = client.isConnected

    /**
     * Runs [command] with [stdin] and returns when the command exits. stdin is written in chunks no larger than the remote
     * window, then closed, so a script on stdin ends where its bytes end, and a command that exits before taking all of it
     * (dd on a full disk) returns its own status and stderr at once, which say why (review F2: one large write held sshj's
     * stream through the transport's 30 s window wait, with the reader thread queued behind it).
     *
     * [deadlineMs] bounds the whole call, stdin included (review F1). Past it the call ends with SshError(TIMEOUT) and the
     * channel is asked to close without waiting for the answer, since OpenSSH holds a session channel's close until its
     * command exits (session.c's "delay detach of session"); the session stays usable. Only when the deadline finds a
     * write in progress, which on a dead path holds sshj's locks inside a socket write, is the socket itself closed. A
     * chunk that makes no progress for 30 s means the link is lost and ends the call as CLOSED, since the keepalive cannot
     * run behind that lock (verify N1).
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
            val writing = AtomicLong(0)
            var writeError: Throwable? = null
            val writer = thread(isDaemon = true, name = "ssh-stdin") {
                writeError = runCatching { writeStdin(cmd, stdin, writing) }.exceptionOrNull()
            }
            val cut = AtomicReference<SshFailure?>(null)
            // the call ends on whichever comes first: the channel's close (the command exited, or the link failed) or
            // the watchdog's verdict, which does not wait for sshd's close
            val done = CountDownLatch(1)
            var joinError: Throwable? = null
            thread(isDaemon = true, name = "ssh-join") {
                joinError = runCatching { cmd.join() }.exceptionOrNull()
                done.countDown()
            }
            val watch = watchdog(cmd, deadlineMs, writing, cut, done)
            fun cutError(cause: Throwable?): SshError? = cut.get()?.let { kind ->
                SshError(kind, if (kind == SshFailure.TIMEOUT) "\"$command\" did not finish in ${deadlineMs / 1000} s"
                else "\"$command\": the link stopped carrying data for ${STALL_MS / 1000} s", cause)
            }
            try {
                // the watchdog acts at the deadline; this bound only stops the wait if it could not
                done.await(deadlineMs + 10_000, TimeUnit.MILLISECONDS)
            } finally {
                watch.interrupt()
            }
            cutError(joinError)?.let { throw it }
            joinError?.let { error ->
                throw SshError(SshFailures.classify(error), "\"$command\": ${error.message}", error)
            }
            writer.join(5_000)
            readers.forEach { it.join(5_000) }
            val status = cmd.exitStatus
            if (status == null) {
                // the channel closed with no exit status (a signal, or a lost link): the write's error, if any, says more
                writeError?.let { throw SshError(SshFailures.classify(it), "write the command's stdin: ${it.message}", it) }
            }
            return ExecResult(stdout.toByteArray(), String(stderr.toByteArray(), Charsets.UTF_8), status)
        }
    }

    /** Never more than the remote window at once, so a write never waits inside sshj's stream for the window to open. */
    private fun writeStdin(cmd: Session.Command, stdin: ByteArray?, writing: AtomicLong) {
        val out = cmd.outputStream
        val data = stdin ?: ByteArray(0)
        var offset = 0
        while (offset < data.size && cmd.isOpen && cmd.exitStatus == null) {
            val window = cmd.remoteWinSize
            if (window <= 0) {
                Thread.sleep(5)
                continue
            }
            val size = minOf(CHUNK.toLong(), window, (data.size - offset).toLong()).toInt()
            writing.set(System.nanoTime())
            out.write(data, offset, size)
            out.flush()
            writing.set(0)
            offset += size
        }
        // the end of stdin; a command that already exited closed its channel, and with it this stream
        if (cmd.isOpen && cmd.exitStatus == null) out.close()
    }

    private fun watchdog(cmd: Session.Command, deadlineMs: Long, writing: AtomicLong, cut: AtomicReference<SshFailure?>,
        done: CountDownLatch): Thread =
        thread(isDaemon = true, name = "ssh-watch") {
            val end = System.nanoTime() + deadlineMs * 1_000_000
            try {
                while (true) {
                    Thread.sleep(250)
                    val now = System.nanoTime()
                    val since = writing.get()
                    val kind = when {
                        since != 0L && now - since > STALL_MS * 1_000_000 -> SshFailure.CLOSED
                        now > end -> SshFailure.TIMEOUT
                        else -> null
                    } ?: continue
                    cut.set(kind)
                    if (kind == SshFailure.TIMEOUT && writing.get() == 0L) {
                        // no write holds sshj's locks: the channel is asked to close and the session stays; sshd answers
                        // once the command exits, and the closer's own wait is bounded by the connection's timeout
                        thread(isDaemon = true, name = "ssh-close") { runCatching { cmd.close() } }
                    } else {
                        // a write in progress on a dead path holds sshj's locks: the socket, which takes none, is closed and
                        // every wait on the transport ends
                        runCatching { client.socket?.close() }
                    }
                    done.countDown()
                    return@thread
                }
            } catch (_: InterruptedException) {
                // the call ended first
            }
        }

    /**
     * Ends the session at once, even from another thread while an exec waits on a dead path (an app's Cancel): the socket
     * first, which takes no sshj lock, then sshj's own teardown.
     */
    override fun close() {
        runCatching { client.socket?.close() }
        runCatching { client.disconnect() }
        runCatching { client.close() }
    }

    companion object {
        /** stdin's chunk: no more than this, nor than the remote window, per write. */
        private const val CHUNK = 32 * 1024

        /** A chunk that makes no progress this long: the link is lost (the keepalive's own bound, which cannot run then). */
        private const val STALL_MS = 30_000L

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
            val config = DefaultConfig().apply {
                keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
                // sshj 0.38 offers an RSA certificate only as ssh-rsa-cert-v01, signed with SHA-1, which OpenSSH 8.8 and
                // later refuse (review F3): the SHA-2 forms go just before it, so nothing else in the order moves (the
                // host-key preference included, which the pins follow)
                val at = keyAlgorithms.indexOfFirst { it.name == "ssh-rsa-cert-v01@openssh.com" }.let { if (it < 0) keyAlgorithms.size else it }
                keyAlgorithms = keyAlgorithms.toMutableList().apply {
                    addAll(at, listOf(
                        KeyAlgorithms.Factory("rsa-sha2-512-cert-v01@openssh.com", SignatureRSA.FactoryRSASHA512(), KeyType.RSA_CERT),
                        KeyAlgorithms.Factory("rsa-sha2-256-cert-v01@openssh.com", SignatureRSA.FactoryRSASHA256(), KeyType.RSA_CERT),
                    ))
                }
            }
            return SSHClient(config).apply {
                connectTimeout = timeouts.connectMs
                // the socket's read bound only matters for the banner (sshj's reader carries on past a timeout after
                // connect): the handshake's, not 60 s twice over (review F5)
                timeout = timeouts.handshakeMs
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
            val encrypted = encryptedKeyText(key.privateKey)
            val provider = try {
                SSHClient(DefaultConfig()).loadKeys(key.privateKey, null, finder)
            } catch (error: Exception) {
                throw keyError(error, passphrase != null, encrypted)
            }
            try {
                // sshj reads a key lazily; read it now so a bad key fails here, not at the server
                check(provider.public != null && provider.private != null) { "the key file holds no key" }
            } catch (error: Exception) {
                throw keyError(error, passphrase != null, encrypted)
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

        /**
         * A legacy PEM key that says it is encrypted (`Proc-Type: 4,ENCRYPTED`). A wrong passphrase decrypts it to bytes whose CBC
         * padding passes by chance about once in 255 tries, and those then fail in the key's own parser («problem creating … private
         * key»), not as a passphrase (CI, 2026-11-24). PKCS#8 (BouncyCastle wraps every failure of its decryption) and PuTTY files
         * (sshj checks their MAC) name a wrong passphrase already (B1 fix review F1).
         */
        private fun encryptedKeyText(text: String): Boolean = "Proc-Type: 4,ENCRYPTED" in text

        private fun keyError(error: Exception, hadPassphrase: Boolean, encrypted: Boolean): SshError {
            val message = error.message.orEmpty().lowercase()
            // a passphrase given for a legacy PEM key that says it is encrypted, and its decrypted bytes failing as a key: the
            // passphrase is wrong (a file damaged in its structure fails before, as a format; damage inside the encrypted body
            // cannot be told from a wrong passphrase: B1 fix review F1 and its verify)
            val passphraseProblem = SshFailures.classify(error) == SshFailure.KEY_PASSPHRASE ||
                (hadPassphrase && encrypted && "problem creating" in message) ||
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

