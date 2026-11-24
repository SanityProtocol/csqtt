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

private val SUDO_N = listOf("env", "LC_ALL=C", "sudo", "-n", "/bin/sh", "-c", "true")
private val SUDO_S = listOf("env", "LC_ALL=C", "sudo", "-S", "/bin/sh", "-c", "true")

class PrivilegeTest {
    private val password = "s3cret-sudo"

    private fun box(uid: String = "1000", sudoN: ExecResult = fails(1, "sudo: a password is required"), sudoV: (ByteArray?) -> ExecResult = {
        if (it != null && String(it) == "$password\n") ok() else fails(1, "sudo: 1 incorrect password attempt")
    }) = FakeRemote { words, stdin ->
        when (words) {
            listOf("id", "-u") -> ok("$uid\n")
            SUDO_N -> sudoN
            SUDO_S -> sudoV(stdin)
            else -> fail("unexpected command $words").let { ok() }
        }
    }

    @Test
    fun rootIsRoot() {
        assertEquals(Privilege.Root, Privilege.detect(box(uid = "0"), null))
    }

    @Test
    fun aGreetingRcFileDoesNotHideRoot() {
        // the zoo's noisy box (Debian 12, 2026-11-24): root's .bashrc echoes before every command, so `id -u` printed the
        // greeting and then 0, and root was read as a sudo user (on a box without sudo: refused as one with no root at all)
        val noisy = FakeRemote { words, _ ->
            if (words == listOf("id", "-u")) ok("zoo-noise: bashrc says hello\n0\n") else fails(127, "sudo: command not found")
        }
        assertEquals(Privilege.Root, Privilege.detect(noisy, null))
        // and an upload's private directory and checksum are read past the same greeting
        var written: ByteArray? = null
        val remote = FakeRemote { words, stdin ->
            when (words.firstOrNull()) {
                "mktemp" -> ok("hello from .bashrc\n/tmp/tmp.Zx9\n")
                "dd" -> { written = stdin; ok("hello from .bashrc\n") }
                "sha256sum" -> ok("hello from .bashrc\n${sha(written!!)}  ${words[1]}\n")
                else -> ok("hello from .bashrc\n")
            }
        }
        val dir = Uploads.privateDir(remote)
        assertEquals("/tmp/tmp.Zx9", dir)
        Uploads.writeFile(remote, "$dir/core", ByteArray(100) { it.toByte() })
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
        assertEquals(1, remote.calls.count { it.first == SUDO_S })
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
        // a login sudo allows some commands only: the probe asks for /bin/sh itself (classic sudo's -v would have passed)
        assertEquals(
            PrivilegeFailure.NOT_IN_SUDOERS,
            kindOf(box(sudoV = { fails(1, "Sorry, user zoo is not allowed to execute '/bin/sh -c true' as root on vps.") }), password),
        )
        // a refused password keeps sudo's own last word
        try {
            Privilege.detect(box(sudoV = { fails(1, "[sudo] password for zoo: \nsudo: 1 incorrect password attempt") }), "wrong")
            fail("detected")
        } catch (error: PrivilegeError) {
            assertTrue(error.detail, error.detail.endsWith("sudo: 1 incorrect password attempt"))
        }
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
                listOf("df", "-Pk", "/tmp", "/var/tmp"),
                listOf("mktemp", "-d", "-p", "/tmp"),
                listOf("dd", "of=/tmp/tmp.Ab12Cd/script.sh", "bs=65536"),
                listOf("sha256sum", "/tmp/tmp.Ab12Cd/script.sh"),
                listOf("sudo", "-S", "/bin/sh", "/tmp/tmp.Ab12Cd/script.sh", "a1"),
                listOf("rm", "-rf", "/tmp/tmp.Ab12Cd"),
            ),
            words,
        )
        assertArrayEquals("$password\n".toByteArray(), withPassword.calls[4].second)
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
        // /tmp refused: /var/tmp
        val varTmp = FakeRemote { words, _ -> if ("/tmp" in words) fails(1, "mktemp: failed") else ok("/var/tmp/tmp.Qw\n") }
        assertEquals("/var/tmp/tmp.Qw", Uploads.privateDir(varTmp))
        // a full tmpfs /tmp, where mktemp still works and only the write would fail: /var/tmp first, by df
        val fullTmp = FakeRemote { words, _ ->
            when {
                words.first() == "df" -> ok("Filesystem 1024-blocks Used Available Capacity Mounted on\n" +
                    "tmpfs 2048 2048 0 100% /tmp\n/dev/vda1 20000000 1000000 19000000 5% /\n")
                words == listOf("mktemp", "-d", "-p", "/var/tmp") -> ok("/var/tmp/tmp.Vt\n")
                else -> ok("/tmp/tmp.Full\n")
            }
        }
        assertEquals("/var/tmp/tmp.Vt", Uploads.privateDir(fullTmp, 20_000))
        // and /tmp with room keeps the first place
        val roomy = FakeRemote { words, _ ->
            if (words.first() == "df") ok("Filesystem 1024-blocks Used Available Capacity Mounted on\ntmpfs 2048000 0 2048000 0% /tmp\n/dev/vda1 1 1 0 100% /\n")
            else ok(if ("/tmp" in words) "/tmp/tmp.Ok\n" else "/var/tmp/tmp.No\n")
        }
        assertEquals("/tmp/tmp.Ok", Uploads.privateDir(roomy, 20_000))
    }
}
