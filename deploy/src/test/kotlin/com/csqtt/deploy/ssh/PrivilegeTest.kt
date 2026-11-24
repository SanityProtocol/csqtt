// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.ssh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

/** A box that answers commands from a script of rules, and records every call (its words and its stdin). */
private class FakeRemote(private val answer: (List<String>, ByteArray?) -> ExecResult) : Remote {
    val calls = mutableListOf<Pair<List<String>, ByteArray?>>()

    override fun exec(command: RemoteCommand, stdin: ByteArray?, deadlineMs: Long): ExecResult {
        calls += command.words to stdin
        return answer(command.words, stdin)
    }
}

private fun ok(out: String = "") = ExecResult(out.toByteArray(), "", 0)
private fun fails(status: Int, err: String) = ExecResult(ByteArray(0), err, status)
private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

class PrivilegeTest {
    private val password = "s3cret-sudo"

    private fun box(uid: String = "1000", sudoN: ExecResult = fails(1, "sudo: a password is required"), sudoV: (ByteArray?) -> ExecResult = {
        if (it != null && String(it) == "$password\n") ok() else fails(1, "sudo: 1 incorrect password attempt")
    }) = FakeRemote { words, stdin ->
        when (words) {
            listOf("id", "-u") -> ok("$uid\n")
            listOf("sudo", "-n", "true") -> sudoN
            listOf("sudo", "-S", "-v") -> sudoV(stdin)
            else -> fail("unexpected command $words").let { ok() }
        }
    }

    @Test
    fun rootIsRoot() {
        assertEquals(Privilege.Root, Privilege.detect(box(uid = "0"), null))
    }

    @Test
    fun nopasswdSudoNeedsNoPassword() {
        assertEquals(Privilege.SudoNoPassword, Privilege.detect(box(sudoN = ok()), null))
    }

    @Test
    fun aSudoPasswordIsCheckedOnceOnStdinAndNeverInArgv() {
        val remote = box()
        val privilege = Privilege.detect(remote, password)
        assertTrue(privilege is Privilege.SudoPassword)
        for ((words, _) in remote.calls) assertFalse("the password reached argv: $words", words.any { password in it })
        assertEquals(1, remote.calls.count { it.first == listOf("sudo", "-S", "-v") })
    }

    @Test
    fun eachRefusalHasItsKind() {
        fun kindOf(remote: Remote, given: String?): PrivilegeFailure =
            try {
                Privilege.detect(remote, given)
                fail("detected").let { PrivilegeFailure.NO_SUDO }
            } catch (error: PrivilegeError) {
                error.kind
            }
        assertEquals(PrivilegeFailure.SUDO_PASSWORD_NEEDED, kindOf(box(), null))
        assertEquals(PrivilegeFailure.SUDO_PASSWORD_WRONG, kindOf(box(), "wrong"))
        assertEquals(PrivilegeFailure.NO_SUDO, kindOf(box(sudoN = fails(127, "bash: sudo: command not found")), password))
        assertEquals(PrivilegeFailure.NO_SUDO, kindOf(box(sudoN = fails(127, "fish: Unknown command: sudo")), password))
        assertEquals(
            PrivilegeFailure.NOT_IN_SUDOERS,
            kindOf(box(sudoN = fails(1, "Sorry, user zoo may not run sudo on vps.")), password),
        )
        assertEquals(
            PrivilegeFailure.NOT_IN_SUDOERS,
            kindOf(box(sudoV = { fails(1, "zoo is not in the sudoers file.  This incident will be reported.") }), password),
        )
    }

    @Test
    fun aScriptRunsAsRootOnStdinOrFromAPrivateFile() {
        val script = "echo hello\n".toByteArray()
        // root and NOPASSWD: the script on stdin, nothing written on the box
        val root = FakeRemote { _, _ -> ok() }
        RootRunner(root, Privilege.Root).runScript(script, listOf("a1"))
        assertEquals(listOf("/bin/sh", "-s", "a1"), root.calls.single().first)
        assertArrayEquals(script, root.calls.single().second)
        val nopasswd = FakeRemote { _, _ -> ok() }
        RootRunner(nopasswd, Privilege.SudoNoPassword).runScript(script)
        assertEquals(listOf("sudo", "-n", "/bin/sh", "-s"), nopasswd.calls.single().first)

        // a sudo password: the script to a private file, checked, run by path with only the password on stdin, then removed
        var written: ByteArray? = null
        val withPassword = FakeRemote { words, stdin ->
            when {
                words == listOf("mktemp", "-d", "-p", "/tmp") -> ok("/tmp/tmp.Ab12Cd\n")
                words.firstOrNull() == "dd" -> { written = stdin; ok() }
                words.firstOrNull() == "sha256sum" -> ok("${sha(written!!)}  ${words[1]}\n")
                else -> ok()
            }
        }
        RootRunner(withPassword, Privilege.SudoPassword(password)).runScript(script, listOf("a1"))
        val words = withPassword.calls.map { it.first }
        assertEquals(
            listOf(
                listOf("mktemp", "-d", "-p", "/tmp"),
                listOf("dd", "of=/tmp/tmp.Ab12Cd/script.sh", "bs=65536"),
                listOf("sha256sum", "/tmp/tmp.Ab12Cd/script.sh"),
                listOf("sudo", "-S", "/bin/sh", "/tmp/tmp.Ab12Cd/script.sh", "a1"),
                listOf("rm", "-rf", "/tmp/tmp.Ab12Cd"),
            ),
            words,
        )
        assertArrayEquals("$password\n".toByteArray(), withPassword.calls[3].second)
        assertArrayEquals(script, written)
    }

    @Test
    fun anUploadThatArrivesDamagedOrCannotBeWrittenSaysSo() {
        val full = FakeRemote { words, _ ->
            when (words.firstOrNull()) {
                "dd" -> fails(1, "dd: error writing '/tmp/tmp.X/core': No space left on device\n2+0 records in")
                else -> ok("/tmp/tmp.X\n")
            }
        }
        try {
            Uploads.writeFile(full, "/tmp/tmp.X/core", ByteArray(10))
            fail("written")
        } catch (error: SshError) {
            assertTrue(error.detail, "No space left on device" in error.detail)
        }
        val damaged = FakeRemote { words, _ -> if (words.firstOrNull() == "sha256sum") ok("0".repeat(64) + "  x\n") else ok() }
        try {
            Uploads.writeFile(damaged, "/tmp/tmp.X/core", ByteArray(10))
            fail("accepted a wrong sum")
        } catch (error: SshError) {
            assertTrue(error.detail, "damaged" in error.detail)
        }
        // /tmp refused (a full tmpfs): /var/tmp
        val varTmp = FakeRemote { words, _ -> if ("/tmp" in words) fails(1, "mktemp: failed") else ok("/var/tmp/tmp.Qw\n") }
        assertEquals("/var/tmp/tmp.Qw", Uploads.privateDir(varTmp))
    }
}
