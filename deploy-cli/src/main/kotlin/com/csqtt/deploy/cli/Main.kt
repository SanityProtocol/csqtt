// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.cli

import com.csqtt.deploy.bundle.Bundle
import com.csqtt.deploy.ssh.HostKeyChanged
import com.csqtt.deploy.ssh.HostKeyPins
import com.csqtt.deploy.ssh.HostKeys
import com.csqtt.deploy.ssh.RemoteCommand
import com.csqtt.deploy.ssh.SshAuth
import com.csqtt.deploy.ssh.SshError
import com.csqtt.deploy.ssh.SshSession
import com.csqtt.deploy.ssh.SshTarget
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
                            and only the one the last refusal showed
exit: 0 done · 2 usage · 3 the host key changed (both fingerprints printed) · 4 an SSH failure (its kind printed)"""

fun main(args: Array<String>) {
    val code = when {
        args.toList() == listOf("bundle-check") -> bundleCheck()
        args.size == 2 && args[0] == "ssh-check" -> sshCheck(File(args[1]))
        else -> {
            System.err.println(USAGE)
            2
        }
    }
    exitProcess(code)
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

private fun properties(targetFile: File) = Properties().apply { targetFile.inputStream().use { load(it) } }

private fun Properties.prop(name: String): String? = getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }

private fun targetOf(p: Properties): SshTarget {
    val auth = p.prop("key_file")?.let { keyFile ->
        SshAuth.Key(File(keyFile).readText(), p.prop("passphrase"), p.prop("cert_file")?.let { File(it).readText() })
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

