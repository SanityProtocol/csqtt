// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.ssh

import com.csqtt.deploy.bundle.Bundle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class LoginKeyTest {
    private val home = kotlin.io.path.createTempDirectory("csqtt-home").toFile()

    @After
    fun cleanUp() {
        home.deleteRecursively()
    }

    @Test
    fun theKeyTheAppMakesIsOneSshjSignsInWith() {
        val key = LoginKey.generate()
        val provider = SshSession.loadKey(SshAuth.Key(key.privateKey))
        assertEquals(key.fingerprint, HostKeys.fingerprint(provider.public))
        assertEquals("ssh-ed25519", key.type)
    }

    /** authkey.sh, run as the engine runs it (marker.sh then authkey.sh on stdin), on a real sh with HOME in a temp dir. */
    private val box = Remote { command, stdin, _ ->
        val out = File(home, ".out")
        val process = ProcessBuilder(listOf("sh") + command.words.drop(1)).directory(home).redirectOutput(out)
            .redirectError(ProcessBuilder.Redirect.DISCARD).apply { environment()["HOME"] = home.path }.start()
        process.outputStream.use { input -> stdin?.let { input.write(it) } }
        check(process.waitFor(30, TimeUnit.SECONDS)) { "sh hung" }
        ExecResult(out.readBytes(), "", process.exitValue())
    }

    private fun mode(file: File): String = java.nio.file.Files.getPosixFilePermissions(file.toPath())
        .fold(0) { acc, p -> acc or (1 shl (8 - p.ordinal)) }.let { Integer.toOctalString(it) }

    @Test
    fun exactlyOurLineComesAndGoesAndTheUsersModesStay() {
        val ssh = File(home, ".ssh").apply { mkdirs() }
        val keys = File(ssh, "authorized_keys").apply { writeText("ssh-rsa AAAAtheirs admin@laptop") }
        java.nio.file.Files.setPosixFilePermissions(ssh.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
        java.nio.file.Files.setPosixFilePermissions(keys.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))
        val key = LoginKey.generate()
        assertEquals(LoginKeyState.ADDED, LoginKeys.install(box, key))
        assertEquals(LoginKeyState.PRESENT, LoginKeys.install(box, key))
        val lines = keys.readLines()
        assertEquals(listOf("ssh-rsa AAAAtheirs admin@laptop",
            "restrict,port-forwarding,permitopen=\"127.0.0.1:*\" ssh-ed25519 ${key.publicBlob} sanityprotocol-csqtt"), lines)
        assertEquals(LoginKeyState.REMOVED, LoginKeys.remove(box, key.publicBlob))
        assertEquals(LoginKeyState.ABSENT, LoginKeys.remove(box, key.publicBlob))
        assertEquals(listOf("ssh-rsa AAAAtheirs admin@laptop"), keys.readLines())
        assertEquals("755", mode(ssh))
        assertEquals("644", mode(keys))
        assertEquals(listOf("authorized_keys"), ssh.list()!!.toList())
    }

    @Test
    fun whatTheScriptCreatesGetsStrictModes() {
        val key = LoginKey.generate()
        assertEquals(LoginKeyState.ADDED, LoginKeys.install(box, key))
        val ssh = File(home, ".ssh")
        assertEquals("700", mode(ssh))
        assertEquals("600", mode(File(ssh, "authorized_keys")))
    }

    @Test
    fun theScriptShipsWithTheBundle() {
        assertEquals(true, Bundle.FILES.contains("lib/authkey.sh"))
    }
}
