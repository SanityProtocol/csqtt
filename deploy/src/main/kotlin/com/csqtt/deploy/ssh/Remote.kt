// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//
// Root on a VPS, uploads to it, and the app's own login key (CSQTT-APP-PLAN §6.3 "Privileges", "Uploads", «Вход без пароля»;
// P2 B2). Everything here runs through [Remote], the one call it needs from an SSH session, so the decisions are tested
// without a server.
package com.csqtt.deploy.ssh

/** What a session must do for B2: run one command. SshSession is the real one; tests have their own. */
fun interface Remote {
    fun exec(command: RemoteCommand, stdin: ByteArray?, deadlineMs: Long): ExecResult
}

fun SshSession.asRemote(): Remote = Remote { command, stdin, deadline -> exec(command, stdin, deadline) }

/**
 * A plain command's own answer: its last non-empty line. The login shell runs the user's rc files first, and many print
 * (a .bashrc that greets, a motd script); their lines come before the command's, never after (a non-login exec runs no
 * logout script). Scripts of the bundle need none of this: their marker lines are found among any noise.
 */
internal fun ExecResult.answer(): String = stdoutText.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()

/** How this login becomes root (M-145, #30). Decided once per session by [Privilege.detect]. */
sealed interface Privilege {
    /** Logged in as root. */
    data object Root : Privilege

    /** sudo without a password (NOPASSWD, or credentials sudo still holds). */
    data object SudoNoPassword : Privilege

    /** sudo with this password, which travels once per command on stdin and never in argv (M-172, #4). */
    class SudoPassword(val password: String) : Privilege {
        override fun toString() = "SudoPassword(…)"
    }

    companion object {
        private const val DEADLINE = 20_000L
        private val REFUSED = listOf("not in the sudoers", "not allowed", "may not run sudo", "afraid i can't")
        private val C_LOCALE = arrayOf("env", "LC_ALL=C")
        private val SHELL_PROBE = arrayOf("/bin/sh", "-c", "true")

        /**
         * `id -u`, then whether sudo runs `/bin/sh` without a password, then, only when sudo asks for one, the given password
         * once (on stdin). The probes ask for exactly what every run needs, `/bin/sh` as root: a login sudo allows only some
         * commands is refused here, by both sudos alike (classic sudo's `-v` would pass it, and the run fail later). They run
         * in the C locale, since the refusals are told apart by their English words and pam_env hands sudo the box's
         * locale (uk, de, fr, pl, tr, pt_BR and zh_CN translate them). No `-p ''`: without -p sudo's prompt goes to stderr,
         * which nothing parses, so every word stays a safe word.
         */
        fun detect(remote: Remote, sudoPassword: String?): Privilege {
            val id = remote.exec(RemoteCommand.of("id", "-u"), null, DEADLINE)
            if (id.exitStatus == 0 && id.answer() == "0") return Root
            val probe = remote.exec(RemoteCommand.of(*C_LOCALE, "sudo", "-n", *SHELL_PROBE), null, DEADLINE)
            if (probe.exitStatus == 0) return SudoNoPassword
            val said = probe.stderr.lowercase()
            // the login shell's own "not found" (exit 127): no sudo at all, doas-only included
            if (probe.exitStatus == 127 || "not found" in said || "no such file" in said || "unknown command" in said) {
                throw PrivilegeError(PrivilegeFailure.NO_SUDO, "sudo is not installed")
            }
            if (REFUSED.any { it in said }) throw PrivilegeError(PrivilegeFailure.NOT_IN_SUDOERS, probe.stderr.trim().take(200))
            val password = sudoPassword?.takeIf { it.isNotEmpty() }
                ?: throw PrivilegeError(PrivilegeFailure.SUDO_PASSWORD_NEEDED, "sudo asks for a password")
            val check = remote.exec(RemoteCommand.of(*C_LOCALE, "sudo", "-S", *SHELL_PROBE), "$password\n".toByteArray(), DEADLINE)
            if (check.exitStatus == 0) return SudoPassword(password)
            if (REFUSED.any { it in check.stderr.lowercase() }) {
                throw PrivilegeError(PrivilegeFailure.NOT_IN_SUDOERS, check.stderr.trim().take(200))
            }
            // sudo's own last word (an attempt count, a lockout by pam_faillock) is kept: it never carries the password
            val last = check.stderr.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty().take(160)
            throw PrivilegeError(PrivilegeFailure.SUDO_PASSWORD_WRONG, "sudo refused the password" + if (last.isEmpty()) "" else ": $last")
        }
    }
}

enum class PrivilegeFailure {
    /** No sudo on the box (doas-only, or none): «Нужны права root: войдите как root или дайте пользователю sudo». */
    NO_SUDO,

    /** sudo wants a password and none was given: the app asks for it (it is no longer forced empty for key logins). */
    SUDO_PASSWORD_NEEDED,

    /** The password sudo was given is wrong. Never retried: sudo counts failures too. */
    SUDO_PASSWORD_WRONG,

    /** sudo answers, but this user may not use it. */
    NOT_IN_SUDOERS,
}

class PrivilegeError(val kind: PrivilegeFailure, val detail: String) : Exception("$kind: $detail")

/**
 * Runs a script of the bundle as root.
 * - Root, or sudo without a password: the script goes on stdin to `/bin/sh -s` (nothing written on the box, §6.2 step 2).
 * - sudo with a password: stdin carries the password line only, and the script runs from a private temporary file that is
 *   removed afterwards. sudo-rs (Ubuntu 26.04) may read its password through a buffer that would swallow a script sent
 *   behind it on the same stdin; classic sudo reads byte by byte, but one path for both is the robust one.
 */
class RootRunner(private val remote: Remote, private val privilege: Privilege) {
    fun runScript(script: ByteArray, args: List<String> = emptyList(), deadlineMs: Long = 60_000): ExecResult {
        return when (privilege) {
            Privilege.Root -> remote.exec(RemoteCommand.of("/bin/sh", "-s", *args.toTypedArray()), script, deadlineMs)
            Privilege.SudoNoPassword ->
                remote.exec(RemoteCommand.of("sudo", "-n", "/bin/sh", "-s", *args.toTypedArray()), script, deadlineMs)
            is Privilege.SudoPassword -> {
                val dir = Uploads.privateDir(remote, script.size.toLong())
                try {
                    val path = "$dir/script.sh"
                    Uploads.writeFile(remote, path, script)
                    remote.exec(RemoteCommand.of("sudo", "-S", "/bin/sh", path, *args.toTypedArray()),
                        "${privilege.password}\n".toByteArray(), deadlineMs)
                } finally {
                    Uploads.removeDir(remote, dir)
                }
            }
        }
    }

    /** One command of the bundle run by path as root (run.sh), with the password on stdin when sudo needs one. */
    fun run(command: RemoteCommand, deadlineMs: Long = 60_000): ExecResult = when (privilege) {
        Privilege.Root -> remote.exec(command, null, deadlineMs)
        Privilege.SudoNoPassword -> remote.exec(RemoteCommand.of("sudo", "-n", *command.words.toTypedArray()), null, deadlineMs)
        is Privilege.SudoPassword -> remote.exec(RemoteCommand.of("sudo", "-S", *command.words.toTypedArray()),
            "${privilege.password}\n".toByteArray(), deadlineMs)
    }
}

/**
 * Private, checked uploads (M-132, M-156, DE-T5/T6): a fresh `mktemp -d` (0700, the login user's) per run, so no stale file
 * of another login is ever in the way; bytes through `dd of=<path>` on the exec channel's stdin, never argv or base64 (a core
 * is ~10 MB, far past MAX_ARG_STRLEN); and each file's sha256 checked on the box. A full /tmp (a small tmpfs) falls back to
 * /var/tmp. Errors carry what the box said, never «проверьте Wi-Fi».
 */
object Uploads {
    private const val DEADLINE = 30_000L
    private val PATH = Regex("/(tmp|var/tmp)/tmp\\.[A-Za-z0-9]+")

    /**
     * A fresh private directory, in /tmp unless /tmp lacks room for [needBytes] twice over (plus 1 MiB): a full tmpfs
     * /tmp (Debian 13's, Fedora's and Arch's default) still lets mktemp make a directory, and only the write would fail.
     */
    fun privateDir(remote: Remote, needBytes: Long = 0): String {
        for (parent in parentsByRoom(remote, needBytes)) {
            val made = remote.exec(RemoteCommand.of("mktemp", "-d", "-p", parent), null, DEADLINE)
            val path = made.answer()
            if (made.exitStatus == 0 && PATH.matches(path)) return path
        }
        throw SshError(SshFailure.OTHER, "mktemp -d failed in /tmp and /var/tmp")
    }

    private fun parentsByRoom(remote: Remote, needBytes: Long): List<String> {
        // in the C locale: df translates its -P header too («Файловая система»), which would hide the rows (verify of F4)
        val df = runCatching { remote.exec(RemoteCommand.of("env", "LC_ALL=C", "df", "-Pk", "/tmp", "/var/tmp"), null, DEADLINE) }.getOrNull()
        // df prints its rows in the order asked, after its header (and after anything an rc file printed)
        val rows = df?.stdoutText?.lines()?.dropWhile { !it.startsWith("Filesystem") }?.drop(1)?.filter { it.isNotBlank() }.orEmpty()
        val tmpFreeKb = rows.firstOrNull()?.trim()?.split(Regex("\\s+"))?.getOrNull(3)?.toLongOrNull()
        val needKb = needBytes / 1024 * 2 + 1024
        return if (tmpFreeKb != null && tmpFreeKb < needKb) listOf("/var/tmp", "/tmp") else listOf("/tmp", "/var/tmp")
    }

    /** Writes [bytes] to [path] (inside a privateDir) and checks its sha256 there. A large file: ~10 MB at the link's speed. */
    fun writeFile(remote: Remote, path: String, bytes: ByteArray, deadlineMs: Long = 600_000) {
        val written = remote.exec(RemoteCommand.of("dd", "of=$path", "bs=65536"), bytes, deadlineMs)
        if (written.exitStatus != 0) {
            // dd prints its own record counts after the error: the error is the line that is not one of them
            val why = written.stderr.lines().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && "records in" !in it && "records out" !in it && "bytes" !in it }
            throw SshError(SshFailure.OTHER, "writing $path failed: ${why ?: written.stderr.trim()}")
        }
        val sum = remote.exec(RemoteCommand.of("sha256sum", path), null, DEADLINE)
        val got = sum.answer().substringBefore(' ')
        val want = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (sum.exitStatus != 0 || got != want) {
            throw SshError(SshFailure.OTHER, "$path arrived damaged: sha256 $got, sent $want")
        }
    }

    fun removeDir(remote: Remote, dir: String) {
        if (PATH.matches(dir)) runCatching { remote.exec(RemoteCommand.of("rm", "-rf", dir), null, DEADLINE) }
    }
}

