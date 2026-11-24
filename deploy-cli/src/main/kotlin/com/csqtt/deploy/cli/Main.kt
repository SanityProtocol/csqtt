// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.cli

import com.csqtt.deploy.bundle.Bundle
import com.csqtt.deploy.inspect.Preflight
import com.csqtt.deploy.inspect.PreflightRules
import com.csqtt.deploy.ssh.HostKeyChanged
import com.csqtt.deploy.ssh.HostKeyPins
import com.csqtt.deploy.ssh.HostKeys
import com.csqtt.deploy.ssh.LoginKey
import com.csqtt.deploy.ssh.LoginKeys
import com.csqtt.deploy.ssh.Privilege
import com.csqtt.deploy.ssh.PrivilegeError
import com.csqtt.deploy.ssh.RemoteCommand
import com.csqtt.deploy.ssh.RootRunner
import com.csqtt.deploy.ssh.SshAuth
import com.csqtt.deploy.ssh.SshError
import com.csqtt.deploy.ssh.SshSession
import com.csqtt.deploy.ssh.SshTarget
import com.csqtt.deploy.ssh.asRemote
import com.csqtt.deploy.wire.LineCodec
import com.csqtt.deploy.wire.Report
import java.io.File
import java.util.Properties
import kotlin.system.exitProcess

private const val USAGE = """usage: deploy-cli <command>
  bundle-check            the bundle this build carries: each file's sha256 and size, then the bundle id (the zoo records it)
  ssh-check <target>      sign in to the VPS a target file describes and run a marker script on stdin; prints its report.
                          The target is a properties file (keep it 0600; nothing secret ever goes on the command line):
                            host, port (22), user, then password=… or key_file=… [passphrase=…] [cert_file=…];
                            pins (the pinned host keys, default ./known-hosts); trust=<fingerprint> pins a changed key,
                            and only the one the last refusal showed; sudo_password=… for a login that needs one
  root-check <target>     how this login becomes root (root, sudo without a password, sudo with one), then a script run as
                          root prints its uid
  login-key <target> add|remove   «Вход без пароля»: add makes a key, SAVES it to <target>.loginkey (0600) and only then
                          installs it for the login user; remove takes exactly that key's line away
  inspect <target> [host…] the environment as root (P2 B3): every check's facts, then each finding with its level; the hosts
                          are the ones the planned run must reach (github.com, the ACME server), none for a plain look
exit: 0 done · 2 usage · 3 the host key changed (both fingerprints printed) · 4 an SSH failure (its kind printed)
      · 5 no root (its kind printed) · 6 the environment stops a run (each FAIL printed)"""

fun main(args: Array<String>) {
    val code = try {
        dispatch(args)
    } catch (error: Exception) {
        // a target file that cannot be read or is missing a field: a usage error, said in one line
        // IllegalArgumentException: SshTarget's own checks (a port past 65535)
        if (error !is IllegalStateException && error !is IllegalArgumentException && error !is java.io.IOException) throw error
        System.err.println("target: ${error.message}")
        2
    }
    exitProcess(code)
}

private fun dispatch(args: Array<String>): Int {
    return when {
        args.toList() == listOf("bundle-check") -> bundleCheck()
        args.size == 2 && args[0] == "ssh-check" -> sshCheck(File(args[1]))
        args.size == 2 && args[0] == "root-check" -> withSession(File(args[1])) { session, p -> rootCheck(session, p) }
        args.size >= 2 && args[0] == "inspect" -> withSession(File(args[1])) { session, p -> inspect(session, p, args.drop(2)) }
        args.size == 3 && args[0] == "login-key" && args[2] in setOf("add", "remove") ->
            withSession(File(args[1])) { session, _ -> loginKey(session, File(args[1] + ".loginkey"), args[2]) }
        else -> {
            System.err.println(USAGE)
            2
        }
    }
}

private fun bundleCheck(): Int {
    for (name in Bundle.FILES) {
        val bytes = Bundle.bytes(name)
        println("${Bundle.sha256(bytes)}  ${bytes.size}  $name")
    }
    println("bundle ${Bundle.id()}")
    return 0
}

/** Host-key pins in a file, one `host:port fingerprint` per line, rewritten whole on a change. */
private class FilePins(private val file: File) : HostKeyPins {
    private val pins: MutableMap<String, String> = if (file.isFile) {
        file.readLines().mapNotNull { line -> line.trim().split(" ").takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap().toMutableMap()
    } else {
        mutableMapOf()
    }

    override fun pinned(host: String, port: Int): String? = pins[HostKeys.entry(host, port)]

    override fun pin(host: String, port: Int, fingerprint: String) {
        pins[HostKeys.entry(host, port)] = fingerprint
        val next = File(file.path + ".new")
        next.writeText(pins.entries.joinToString("") { "${it.key} ${it.value}\n" })
        if (!next.renameTo(file)) error("could not write ${file.path}")
    }
}

/**
 * A target file: UTF-8 `key=value` lines split at the first `=`, nothing escaped, `#` lines skipped. Properties.load read
 * ISO-8859-1 and took backslashes as escapes, so a password holding `\` reached the server altered, and fail2ban counted
 * the failure (review F6).
 */
private fun properties(targetFile: File) = Properties().apply {
    targetFile.readLines(Charsets.UTF_8).forEach { line ->
        val at = line.indexOf('=')
        if (at > 0 && !line.trimStart().startsWith("#")) setProperty(line.substring(0, at).trim(), line.substring(at + 1).removeSuffix("\r"))
    }
}

private fun Properties.prop(name: String): String? = getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }

private fun targetOf(p: Properties): SshTarget {
    val auth = p.prop("key_file")?.let { keyFile ->
        SshAuth.Key(File(keyFile).readText(), p.getProperty("passphrase")?.takeIf { it.isNotEmpty() },
            p.prop("cert_file")?.let { File(it).readText() })
    } ?: SshAuth.Password(p.getProperty("password") ?: error("the target has neither key_file nor password"))
    return SshTarget(p.prop("host") ?: error("the target has no host"), p.prop("port")?.toInt() ?: 22,
        p.prop("user") ?: error("the target has no user"), auth)
}

private fun pinsOf(p: Properties) = FilePins(File(p.prop("pins") ?: "known-hosts"))

private fun sshCheck(targetFile: File): Int {
    val p = properties(targetFile)
    val pins = pinsOf(p)
    val session = try {
        SshSession.open(targetOf(p), pins)
    } catch (change: HostKeyChanged) {
        val trust = p.prop("trust")
        if (trust == null || trust != change.presented) {
            println("host key changed: ${change.host}:${change.port} pinned ${change.pinned}, presented ${change.presented}")
            return 3
        }
        pins.pin(change.host, change.port, change.presented)
        println("host key trusted: ${change.presented}")
        return sshCheck(targetFile)
    } catch (error: SshError) {
        println("${error.kind}: ${error.detail}")
        return 4
    }
    session.use {
        val script = Bundle.bytes("lib/marker.sh") +
            "\ncsqtt_emit HELLO \"\$(uname -m)\" \"\$(id -u)\"\ncsqtt_end\n".toByteArray()
        val result = try {
            it.exec(RemoteCommand.shellFromStdin(), script, deadlineMs = 30_000)
        } catch (error: SshError) {
            println("${error.kind}: ${error.detail}")
            return 4
        }
        return when (val report = LineCodec.parse(result.stdoutText)) {
            is Report.Complete -> {
                report.lines.forEach { line -> println("${line.kind} ${line.fields.joinToString(" ")}") }
                println("exit ${result.exitStatus}")
                if (result.exitStatus == 0) 0 else 4
            }
            is Report.Broken -> {
                println("report broken: ${report.reason}")
                4
            }
        }
    }
}

/** Opens a session to the target (no trust step: ssh-check does that) and runs [body]; SSH failures exit 3 or 4. */
private fun withSession(targetFile: File, body: (SshSession, Properties) -> Int): Int {
    val p = properties(targetFile)
    val session = try {
        SshSession.open(targetOf(p), pinsOf(p))
    } catch (change: HostKeyChanged) {
        println("host key changed: ${change.host}:${change.port} pinned ${change.pinned}, presented ${change.presented}")
        return 3
    } catch (error: SshError) {
        println("${error.kind}: ${error.detail}")
        return 4
    }
    return session.use {
        try {
            body(it, p)
        } catch (error: SshError) {
            println("${error.kind}: ${error.detail}")
            4
        } catch (error: PrivilegeError) {
            println("${error.kind}: ${error.detail}")
            5
        }
    }
}

private fun rootCheck(session: SshSession, p: Properties): Int {
    val remote = session.asRemote()
    val privilege = Privilege.detect(remote, p.getProperty("sudo_password"))
    println("privilege ${privilege.javaClass.simpleName}")
    val script = Bundle.bytes("lib/marker.sh") + "\ncsqtt_emit ROOT \"\$(id -u)\"\ncsqtt_end\n".toByteArray()
    val result = RootRunner(remote, privilege).runScript(script)
    return when (val report = LineCodec.parse(result.stdoutText)) {
        is Report.Complete -> {
            report.lines.forEach { line -> println("${line.kind} ${line.fields.joinToString(" ")}") }
            if (report.lines.any { it.kind == "ROOT" && it.fields == listOf("0") }) 0 else 5
        }
        is Report.Broken -> {
            println("report broken: ${report.reason}")
            4
        }
    }
}

private fun loginKey(session: SshSession, keyFile: File, verb: String): Int {
    val remote = session.asRemote()
    val pubFile = File(keyFile.path + ".pub")
    // <target>.loginkey: the private key alone (0600, a key_file a later target can name); <target>.loginkey.pub its public half
    fun saved(): LoginKey? = if (keyFile.isFile && pubFile.isFile) {
        LoginKey(keyFile.readText(), pubFile.readText().trim().split(" ").getOrNull(1) ?: error("${pubFile.path} holds no public key"))
    } else {
        null
    }
    if (verb == "add") {
        // a key saved by an earlier add is installed again (PRESENT if it is there): a new key each time would leave the
        // earlier one's line on the box with nothing left to remove it
        val key = saved() ?: LoginKey.generate().also { key ->
            // saved first: a key installed and then lost would leave an orphan line on the box
            save(pubFile, "${key.type} ${key.publicBlob}\n")
            save(keyFile, key.privateKey)
            println("saved ${keyFile.path} (${key.fingerprint})")
        }
        println("login key ${LoginKeys.install(remote, key)}")
    } else {
        val key = saved() ?: run {
            println("no saved login key at ${keyFile.path}")
            return 2
        }
        println("login key ${LoginKeys.remove(remote, key.publicBlob)}")
    }
    return 0
}

/** Written 0600 from the start (never at the umask's mode for a moment), then moved into place. */
private fun save(file: File, text: String) {
    val tmp = File(file.path + ".new").toPath()
    java.nio.file.Files.deleteIfExists(tmp)
    java.nio.file.Files.createFile(tmp, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
    java.nio.file.Files.writeString(tmp, text)
    java.nio.file.Files.move(tmp, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE)
}

private fun inspect(session: SshSession, p: Properties, hosts: List<String>): Int {
    val remote = session.asRemote()
    val privilege = Privilege.detect(remote, p.getProperty("sudo_password"))
    val script = Bundle.bytes("lib/marker.sh") + Bundle.bytes("inspect.sh")
    val started = System.nanoTime()
    val result = RootRunner(remote, privilege).runScript(script, hosts, deadlineMs = 60_000)
    val took = (System.nanoTime() - started) / 1_000_000
    val report = LineCodec.parse(result.stdoutText)
    if (report !is Report.Complete) {
        println("report broken: ${(report as Report.Broken).reason}")
        return 4
    }
    // report_file=…: the report exactly as the box sent it (its marker lines, noise dropped), which the zoo keeps as the
    // classifier's test data: it must go through the same parse the app does
    p.getProperty("report_file")?.let { path ->
        File(path).writeText(result.stdoutText.lines().filter { it.startsWith("CSQTT_") }.joinToString("\n", postfix = "\n"))
    }
    report.lines.forEach { line -> println("${line.kind} ${line.fields.joinToString(" ")}") }
    val verdicts = PreflightRules.verdicts(Preflight.from(report.lines), System.currentTimeMillis() / 1000)
    verdicts.forEach { println("${it.level} ${it.finding}" + if (it.detail.isEmpty()) "" else " ${it.detail}") }
    println("inspect took $took ms")
    return if (PreflightRules.stops(verdicts).isEmpty()) 0 else 6
}
