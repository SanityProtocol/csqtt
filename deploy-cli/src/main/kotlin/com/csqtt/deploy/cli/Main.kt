// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.cli

import com.csqtt.deploy.bundle.Bundle
import com.csqtt.deploy.inspect.Preflight
import com.csqtt.deploy.inspect.Classifier
import com.csqtt.deploy.inspect.Inventory
import com.csqtt.deploy.plan.Proposer
import com.csqtt.deploy.plan.ProposalRefused
import com.csqtt.deploy.run.Attached
import com.csqtt.deploy.run.Connection
import com.csqtt.deploy.run.Core
import com.csqtt.deploy.run.CreateRun
import com.csqtt.deploy.run.Credentials
import com.csqtt.deploy.run.Outside
import com.csqtt.deploy.run.PanelAnswer
import com.csqtt.deploy.run.ReadBack
import com.csqtt.deploy.run.ReadBackFailed
import com.csqtt.deploy.run.proposalOfKeptPlan
import com.csqtt.deploy.run.CancelOutcome
import com.csqtt.deploy.run.Coordinator
import com.csqtt.deploy.run.DeployState
import com.csqtt.deploy.run.FileStateStore
import com.csqtt.deploy.run.RunEvent
import com.csqtt.deploy.run.RunPlan
import com.csqtt.deploy.run.RunResult
import com.csqtt.deploy.run.Runner
import com.csqtt.deploy.run.StartOutcome
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
  run <target> selftest [steps] [seconds] [port…]   start the run skeleton's self-test as root on the box (B7a) and
                          follow it; its state is kept in <target>.state, so a killed deploy-cli re-attaches
  attach <target>         follow the run <target>.state names (or say what state it holds)
  cancel <target>         ask that run to stop at its next safe point
  runs <target>           the runs the box keeps: id, live, result (none and not live: interrupted)
  create <target> [id=… iface=… subnet=… udp=… web=… slot=… bind=127.0.0.1 | resume=<run id>]   «Создать новый» (B8): inspect as root,
                          propose a new instance that collides with nothing (the words override the proposal), run it,
                          read the connection back and check it from here: a DTLS ClientHello on its UDP port and the
                          panel over HTTPS, its certificate pinned. The core is the target's core=<file>, core_version=…
                          resume=<run id>: «Продолжить» a create a reboot or a kill cut off, from the plan its run kept
  readback <target> <id>  «Подключиться» to an instance of ours: its connection read back, then the same outside check
  remove <target> <id> [purge]   an instance of ours removed (B11): its store kept for «Восстановить», or with purge, gone
  restore <target> <id>   «Восстановить» a kept store with the target's core: its clients come back
  restart <target> <id>   «Перезапустить» (and «Запустить»): its unit (re)started and verified
  journal <target> <id> [lines]   «Журнал»: its unit's last lines
  remove-foreign <target> <name> [purge]   a stock systemd install removed (B11b; <name> as inspect prints it, its unit):
                          every object it has listed first, then removed; its store kept, or with purge, gone
  repair <target> <code>  «Исправить» a box problem (B11c): B14/B24 a Docker prereq without its helper, B15 the stock
                          certificate timer beside swg's take-over, S1 our sysctl drop-in left with no instance of ours
  rebuild-manifest <target> <id>   «Починить» an instance of ours whose manifest was lost (B11c): rebuilt from what lives
  move <target> <name> [resume=<run id>]   «Обновить» a stock systemd install = move it into our layout (B12; <name> as
                          inspect prints it): our instance on its ports with its store, certificate and panel login, through
                          one marker, the stock install removed after the verify (its store kept); then ours read back and
                          checked from here. resume=<run id>: «Продолжить» a move a reboot or a kill cut off, with its kept plan
  update <target> <id>    an instance of ours to the target's core (B10): stage, switch, verify, rolled back on a failure;
                          then its connection read back (the same passwords and pin) and checked from here
exit: 0 done · 2 usage · 3 the host key changed (both fingerprints printed) · 4 an SSH failure (its kind printed)
      · 5 no root (its kind printed) · 6 the environment stops a run (each FAIL printed) · 7 a run failed (its result
      printed) · 8 another run holds the box · 9 the run was interrupted · 11 no such run on the box (it never started,
      or was pruned), or for cancel, the run had already ended · 10 the run is ok but its server does not answer from
      here (the cause printed)"""

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
        args.size >= 3 && args[0] == "run" && args[2] == "selftest" ->
            withSession(File(args[1])) { session, p -> runSelftest(session, p, File(args[1]), args.drop(3)) }
        args.size >= 2 && args[0] == "create" -> withSession(File(args[1])) { session, p -> create(session, p, File(args[1]), args.drop(2)) }
        args.size == 3 && args[0] == "update" -> withSession(File(args[1])) { session, p -> update(session, p, File(args[1]), args[2]) }
        args.size in 3..4 && args[0] == "remove" && (args.size == 3 || args[3] == "purge") ->
            withSession(File(args[1])) { session, p -> maintain(session, p, File(args[1]), args[2], if (args.size == 4) "purge" else "remove") }
        args.size == 3 && args[0] in setOf("restore", "restart", "rebuild-manifest") ->
            withSession(File(args[1])) { session, p -> maintain(session, p, File(args[1]), args[2], args[0]) }
        args.size == 3 && args[0] == "repair" -> withSession(File(args[1])) { session, p -> repairBox(session, p, File(args[1]), args[2]) }
        args.size in 3..4 && args[0] == "move" && (args.size == 3 || args[3].startsWith("resume=")) ->
            withSession(File(args[1])) { session, p -> move(session, p, File(args[1]), args[2], args.getOrNull(3)?.removePrefix("resume=")) }
        args.size in 3..4 && args[0] == "remove-foreign" && (args.size == 3 || args[3] == "purge") ->
            withSession(File(args[1])) { session, p -> removeForeign(session, p, File(args[1]), args[2], args.size == 4) }
        args.size in 3..4 && args[0] == "journal" -> withSession(File(args[1])) { session, p ->
            com.csqtt.deploy.run.Maintenance.journal(rootOf(session, p), args[2], args.getOrNull(3)?.toIntOrNull() ?: 200).forEach { println(it) }
            0
        }
        args.size == 3 && args[0] == "readback" ->
            withSession(File(args[1])) { session, p -> readBack(rootOf(session, p), args[2], p.prop("host").orEmpty(), null) }
        args.size == 2 && args[0] in setOf("attach", "cancel", "runs") ->
            withSession(File(args[1])) { session, p -> runVerb(session, p, File(args[1]), args[0]) }
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

private fun runner(session: SshSession, p: Properties): Runner {
    val remote = session.asRemote()
    return Runner(remote, RootRunner(remote, Privilege.detect(remote, p.getProperty("sudo_password"))))
}

private fun coordinator(target: File) = Coordinator(FileStateStore(File(target.path + ".state"))).also { c ->
    c.observe { state -> if (state is DeployState.Running && state.phase.isEmpty()) println("state running ${state.runId}") }
}

/** Follows a run to its result, keeping the coordinator's Running state current (the pendingRunId is in it from the start). */
private fun follow(runner: Runner, coordinator: Coordinator, runId: String, before: DeployState = DeployState.Idle): Int {
    var running = DeployState.Running(runId)
    val attached = runner.attach(runId) { event, _ ->
        when (event) {
            is RunEvent.Phase -> running = running.copy(phase = event.name, waitingFor = null)
            is RunEvent.Percent -> running = running.copy(percent = event.value)
            is RunEvent.Beat -> running = running.copy(waitingFor = event.waitingFor)
            else -> {}
        }
        coordinator.move(running)
        println(when (event) {
            is RunEvent.Phase -> "phase ${event.name}"
            is RunEvent.Percent -> "${event.value}%"
            is RunEvent.Beat -> "waiting: ${event.waitingFor}"
            is RunEvent.Step -> "step ${event.name}: ${event.command}"
            is RunEvent.Output -> "  ${event.line}"
            is RunEvent.Result -> "result ${event.result.kind} at ${event.result.step}: ${event.result.cause} ${event.result.next}".trim()
        })
    }
    return when (attached) {
        is Attached.Done -> {
            coordinator.move(DeployState.Finished(attached.result))
            if (attached.result.ok) 0 else 7
        }
        Attached.Interrupted -> {
            coordinator.move(DeployState.Interrupted(runId))
            println("interrupted: the run ended with no result")
            9
        }
        Attached.NotFound -> {
            // its id was kept before the start, and the start never reached the box: back to the state before it (B7b review
            // F1; verify residue: Idle lost a choice that came first)
            coordinator.move(before)
            println("no such run on the box: $runId never started there, or was pruned")
            11
        }
    }
}

private fun runSelftest(session: SshSession, p: Properties, target: File, args: List<String>): Int {
    val runner = runner(session, p)
    val coordinator = coordinator(target)
    val runId = Runner.newRunId()
    val plan = RunPlan("selftest", mapOf("steps" to (args.getOrNull(0) ?: "3"), "step_seconds" to (args.getOrNull(1) ?: "2"),
        "ports" to args.drop(2).joinToString(" ")), mapOf("zoo_secret" to "ZooSecretValue26"))
    // the pendingRunId is kept before the start: a death in between finds the run by it, or finds no run at all
    val before = coordinator.state
    coordinator.move(DeployState.Running(runId))
    return when (val outcome = runner.start(runId, plan)) {
        is StartOutcome.Started -> follow(runner, coordinator, runId, before)
        is StartOutcome.Busy -> {
            // the box's own run is the one to watch; with no id, what was kept before stays (B7b review F2)
            coordinator.move(if (outcome.activeRunId.isNotEmpty()) DeployState.Running(outcome.activeRunId) else before)
            println("busy: run ${outcome.activeRunId} holds the box (attach follows it)")
            8
        }
        is StartOutcome.Refused -> {
            coordinator.move(before)
            println("refused: ${outcome.reason}")
            7
        }
    }
}

private fun runVerb(session: SshSession, p: Properties, target: File, verb: String): Int {
    val runner = runner(session, p)
    if (verb == "runs") {
        runner.status().forEach { println("${it.id} ${it.action} " + if (it.active) "live" else it.result.ifEmpty { "interrupted" }) }
        return 0
    }
    val coordinator = coordinator(target)
    val state = coordinator.state
    val runId = (state as? DeployState.Running)?.runId ?: (state as? DeployState.Interrupted)?.runId
    if (runId == null) {
        println("no run to $verb: the state is ${state::class.simpleName}" + if (state is DeployState.Finished) " (${(state.result as RunResult).kind})" else "")
        return 2
    }
    if (verb == "cancel") {
        return when (val outcome = runner.cancel(runId)) {
            CancelOutcome.Requested -> { println("cancel asked for $runId"); 0 }
            CancelOutcome.Ended -> { println("nothing to cancel: $runId had already ended"); 11 }
            CancelOutcome.NotFound -> { println("no such run on the box: $runId"); 11 }
            is CancelOutcome.Refused -> { println("cancel refused: ${outcome.reason}"); 7 }
        }
    }
    return follow(runner, coordinator, runId)
}

private fun rootOf(session: SshSession, p: Properties): RootRunner {
    val remote = session.asRemote()
    return RootRunner(remote, Privilege.detect(remote, p.getProperty("sudo_password")))
}

/** «Создать новый»: inspect, propose, run, read back, check from outside (P2 B8). */
private fun create(session: SshSession, p: Properties, target: File, words: List<String>): Int {
    val edits = words.associate { it.substringBefore('=') to it.substringAfter('=', "") }
    // every word known and every number a number (B8 review F7: a typo must not fall back to the proposal)
    val unknown = edits.keys - setOf("id", "iface", "subnet", "udp", "web", "slot", "bind", "resume")
    val notNumbers = listOf("udp", "web", "slot").filter { it in edits && edits.getValue(it).toIntOrNull() == null }
    if (unknown.isNotEmpty() || notNumbers.isNotEmpty()) {
        println("unknown words: ${unknown.joinToString()}; not numbers: ${notNumbers.joinToString()}")
        return 2
    }
    val corePath = p.prop("core") ?: return 2.also { println("the target names no core=<file>") }
    val core = Core(File(corePath).readBytes(), p.prop("core_version") ?: return 2.also { println("the target names no core_version=") })
    val remote = session.asRemote()
    val root = RootRunner(remote, Privilege.detect(remote, p.getProperty("sudo_password")))
    val result = root.runScript(Bundle.bytes("lib/marker.sh") + Bundle.bytes("inspect.sh"), emptyList(), deadlineMs = 60_000)
    val report = LineCodec.parse(result.stdoutText) as? Report.Complete ?: return 4.also { println("inspection: report broken") }
    val preflight = Preflight.from(report.lines)
    val verdicts = PreflightRules.verdicts(preflight, System.currentTimeMillis() / 1000)
    verdicts.filter { it.level.name != "OK" }.forEach { println("${it.level} ${it.finding}" + if (it.detail.isEmpty()) "" else " ${it.detail}") }
    if (PreflightRules.stops(verdicts).isNotEmpty()) return 6
    val inventory = Inventory.from(report.lines)
    val classification = Classifier.classify(inventory)
    val runner = Runner(remote, root)
    val resumeOf = edits["resume"]
    if (resumeOf != null) {
        // «Продолжить»: the cut-off run's own plan again, new credentials, and run.sh undoes what that run left first
        // only a create the box lists as cut off (no result, no live unit): a finished one is used by «Подключиться», never
        // made again over its clients (B8 verify F8)
        if (runner.status().none { it.id == resumeOf && it.interrupted && it.action == "create" }) {
            println("run $resumeOf is not a create the box lists as cut off: nothing to continue")
            return 2
        }
        val proposal = runner.keptPlan(resumeOf)?.let { proposalOfKeptPlan(it) }
            ?: return 2.also { println("no create plan kept for run $resumeOf on the box") }
        println("will: «Продолжить» ${proposal.instance} from run $resumeOf: what it left goes, the create runs again")
        return runCreate(remote, root, runner, target, proposal, preflight, core, p, resumeOf = resumeOf)
    }
    val proposed = try {
        Proposer.propose(classification, inventory)
    } catch (refused: ProposalRefused) {
        println("refused: ${refused.reason}")
        return 6
    }
    val proposal = proposed.copy(
        id = edits["id"] ?: proposed.id,
        iface = edits["iface"] ?: proposed.iface,
        subnet = edits["subnet"] ?: proposed.subnet,
        udpPort = edits["udp"]?.toIntOrNull() ?: proposed.udpPort,
        webPort = edits["web"]?.toIntOrNull() ?: proposed.webPort,
        slot = edits["slot"]?.toIntOrNull() ?: proposed.slot,
        webBind = if ("bind" in edits) edits["bind"]?.ifEmpty { null } else proposed.webBind,
    )
    val problems = Proposer.problems(proposal, classification, inventory)
    if (problems.isNotEmpty()) {
        problems.forEach { println("problem ${it.field}: ${it.reason}") }
        return 2
    }
    val plan = Proposer.createPlan(proposal, classification)
    (plan.will + CreateRun.hostChanges(preflight, proposal)).forEach { println("will: $it") }
    plan.unchanged.forEach { println("unchanged: $it") }
    return runCreate(remote, root, runner, target, proposal, preflight, core, p, resumeOf = null)
}

private fun runCreate(remote: com.csqtt.deploy.ssh.Remote, root: RootRunner, runner: Runner, target: File,
    proposal: com.csqtt.deploy.plan.Proposal, preflight: Preflight, core: Core, p: Properties, resumeOf: String?): Int {
    val credentials = Credentials.generate()
    val sshHost = p.prop("host").orEmpty()
    val runPlan = CreateRun.plan(proposal, CreateRun.publicAddress(preflight, sshHost), core, credentials, resumeOf)
    val coordinator = coordinator(target)
    val runId = Runner.newRunId()
    val before = coordinator.state
    coordinator.move(DeployState.Running(runId))
    when (val outcome = runner.start(runId, runPlan)) {
        is StartOutcome.Started -> {}
        is StartOutcome.Busy -> {
            coordinator.move(if (outcome.activeRunId.isNotEmpty()) DeployState.Running(outcome.activeRunId) else before)
            println("busy: run ${outcome.activeRunId} holds the box (attach follows it)")
            return 8
        }
        is StartOutcome.Refused -> {
            coordinator.move(before)
            println("refused: ${outcome.reason}")
            return 7
        }
    }
    val code = follow(runner, coordinator, runId, before)
    if (code != 0) return code
    return readBack(root, proposal.id, sshHost, credentials)
}

/** The connection read back (never printed: its passwords are compared with what was set, if anything was), then the check. */
private fun readBack(root: RootRunner, id: String, sshHost: String, set: Credentials?): Int {
    val connection: Connection = try {
        ReadBack.read(root, id, sshHost)
    } catch (failed: ReadBackFailed) {
        println("readback failed: ${failed.reason}")
        return 7
    }
    // the panel's secret path is a secret: masked here, as the passwords are (B8 review F6)
    val masked = if (connection.secretPath.isEmpty()) connection.panelUrl else connection.panelUrl.replace(connection.panelPath, "/***/")
    println("connection host=${connection.host} udp=${connection.udpPort} web=${connection.webPort} panel=$masked " +
        "login=${connection.webUser} cert=${connection.certSha256 ?: "-"} main_password_sha8=${sha8(connection.mainPassword)}")
    if (set != null) {
        println("readback main_password " + if (connection.mainPassword == set.mainPassword) "matches the one set" else "DIFFERS from the one set")
        println("readback panel_password " + if (connection.webPass == set.webPass) "matches the one set" else "DIFFERS from the one set")
    }
    val outside = Outside.check(connection)
    val panel = when (val answer = outside.panel) {
        null -> "local-only"
        is PanelAnswer.Status -> "http-${answer.code}"
        PanelAnswer.WrongCertificate -> "wrong-certificate"
        is PanelAnswer.NoAnswer -> "no-answer (${answer.reason})"
    }
    println("outside udp=${if (outside.udp) "answers" else "silent"} panel=$panel")
    val cause = outside.cause(connection)
    return if (cause == null) {
        println("outcome ok")
        0
    } else {
        println("outcome ok-unreachable-from-outside: $cause")
        10
    }
}

/** A fingerprint of a secret for comparing two read-backs, never the secret. */
private fun sha8(secret: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(secret.toByteArray()).take(4).joinToString("") { "%02x".format(it) }

/** The environment's stops for a run that brings a core to an instance already there, each printed (exit 6 follows). */
private fun coreRunStopped(report: Report.Complete): Boolean {
    val stops = PreflightRules.stopsACoreRun(PreflightRules.verdicts(Preflight.from(report.lines), System.currentTimeMillis() / 1000))
    stops.forEach { println("FAIL ${it.finding}" + if (it.detail.isEmpty()) "" else " ${it.detail}") }
    return stops.isNotEmpty()
}

/** An instance of ours to the target's core (P2 B10). */
private fun update(session: SshSession, p: Properties, target: File, id: String): Int {
    val corePath = p.prop("core") ?: return 2.also { println("the target names no core=<file>") }
    val core = Core(File(corePath).readBytes(), p.prop("core_version") ?: return 2.also { println("the target names no core_version=") })
    val remote = session.asRemote()
    val root = RootRunner(remote, Privilege.detect(remote, p.getProperty("sudo_password")))
    val result = root.runScript(Bundle.bytes("lib/marker.sh") + Bundle.bytes("inspect.sh"), emptyList(), deadlineMs = 60_000)
    val report = LineCodec.parse(result.stdoutText) as? Report.Complete ?: return 4.also { println("inspection: report broken") }
    if (coreRunStopped(report)) return 6
    val inventory = Inventory.from(report.lines)
    val install = Classifier.classify(inventory).installations.firstOrNull { com.csqtt.deploy.run.UpdateRun.idOf(it) == id }
        ?: return 2.also { println("no instance of ours sqc-$id on this box") }
    println("will: ${install.name} goes to ${core.version} (${core.sha256.take(8)}); its ports, env and certificate stay")
    val runner = Runner(remote, root)
    val coordinator = coordinator(target)
    val runId = Runner.newRunId()
    val before = coordinator.state
    coordinator.move(DeployState.Running(runId))
    when (val outcome = runner.start(runId, com.csqtt.deploy.run.UpdateRun.plan(install, inventory, core))) {
        is StartOutcome.Started -> {}
        is StartOutcome.Busy -> {
            coordinator.move(if (outcome.activeRunId.isNotEmpty()) DeployState.Running(outcome.activeRunId) else before)
            println("busy: run ${outcome.activeRunId} holds the box (attach follows it)")
            return 8
        }
        is StartOutcome.Refused -> {
            coordinator.move(before)
            println("refused: ${outcome.reason}")
            return 7
        }
    }
    val code = follow(runner, coordinator, runId, before)
    if (code != 0) return code
    return readBack(root, id, p.prop("host").orEmpty(), null)
}

/** remove, purge, restore, restart of an instance of ours (P2 B11): inspected first, so the run re-checks what it saw. */
private fun maintain(session: SshSession, p: Properties, target: File, id: String, verb: String): Int {
    val remote = session.asRemote()
    val root = RootRunner(remote, Privilege.detect(remote, p.getProperty("sudo_password")))
    val result = root.runScript(Bundle.bytes("lib/marker.sh") + Bundle.bytes("inspect.sh"), emptyList(), deadlineMs = 60_000)
    val report = LineCodec.parse(result.stdoutText) as? Report.Complete ?: return 4.also { println("inspection: report broken") }
    val install = Classifier.classify(Inventory.from(report.lines)).installations.firstOrNull {
        runCatching { com.csqtt.deploy.run.Maintenance.idOf(it) }.getOrNull() == id
    } ?: return 2.also { println("no instance of ours sqc-$id on this box") }
    val plan = when (verb) {
        "remove" -> com.csqtt.deploy.run.Maintenance.remove(install, purge = false)
        "purge" -> com.csqtt.deploy.run.Maintenance.remove(install, purge = true)
        "restart" -> com.csqtt.deploy.run.Maintenance.restart(install)
        "rebuild-manifest" -> com.csqtt.deploy.run.Maintenance.rebuildManifest(install)
        else -> {
            if (coreRunStopped(report)) return 6
            val corePath = p.prop("core") ?: return 2.also { println("the target names no core=<file>") }
            com.csqtt.deploy.run.Maintenance.restore(install,
                Core(File(corePath).readBytes(), p.prop("core_version") ?: return 2.also { println("the target names no core_version=") }))
        }
    }
    println("will: ${plan.action} ${install.name}")
    val runner = Runner(remote, root)
    val coordinator = coordinator(target)
    val runId = Runner.newRunId()
    val before = coordinator.state
    coordinator.move(DeployState.Running(runId))
    when (val outcome = runner.start(runId, plan)) {
        is StartOutcome.Started -> {}
        is StartOutcome.Busy -> {
            coordinator.move(if (outcome.activeRunId.isNotEmpty()) DeployState.Running(outcome.activeRunId) else before)
            println("busy: run ${outcome.activeRunId} holds the box (attach follows it)")
            return 8
        }
        is StartOutcome.Refused -> {
            coordinator.move(before)
            println("refused: ${outcome.reason}")
            return 7
        }
    }
    val code = follow(runner, coordinator, runId, before)
    if (code != 0 || verb == "remove" || verb == "purge" || verb == "rebuild-manifest") return code
    return readBack(root, id, p.prop("host").orEmpty(), null)
}

/** «Исправить» a box problem (P2 B11c): inspected, the problem with that code found, its repair run. */
private fun repairBox(session: SshSession, p: Properties, target: File, code: String): Int {
    val remote = session.asRemote()
    val root = RootRunner(remote, Privilege.detect(remote, p.getProperty("sudo_password")))
    val result = root.runScript(Bundle.bytes("lib/marker.sh") + Bundle.bytes("inspect.sh"), emptyList(), deadlineMs = 60_000)
    val report = LineCodec.parse(result.stdoutText) as? Report.Complete ?: return 4.also { println("inspection: report broken") }
    val inventory = Inventory.from(report.lines)
    val problem = Classifier.classify(inventory).problems.firstOrNull { it.code == code }
        ?: return 2.also { println("no box problem $code on this box") }
    val fix = com.csqtt.deploy.run.BoxRepair.plan(problem, inventory.dockerStopped)
        ?: return 2.also { println("$code has no repair: ${problem.detail}") }
    val plan = fix.run
    println("will: repair $code (${problem.subject}): ${problem.detail}")
    fix.will.forEach { println("will: $it") }
    val runner = Runner(remote, root)
    val coordinator = coordinator(target)
    val runId = Runner.newRunId()
    val before = coordinator.state
    coordinator.move(DeployState.Running(runId))
    when (val outcome = runner.start(runId, plan)) {
        is StartOutcome.Started -> {}
        is StartOutcome.Busy -> {
            coordinator.move(if (outcome.activeRunId.isNotEmpty()) DeployState.Running(outcome.activeRunId) else before)
            println("busy: run ${outcome.activeRunId} holds the box (attach follows it)")
            return 8
        }
        is StartOutcome.Refused -> {
            coordinator.move(before)
            println("refused: ${outcome.reason}")
            return 7
        }
    }
    return follow(runner, coordinator, runId, before)
}

/** A stock systemd install removed (P2 B11b): inspected, its every object listed as the app lists them, then the run. */
private fun removeForeign(session: SshSession, p: Properties, target: File, name: String, purge: Boolean): Int {
    val remote = session.asRemote()
    val root = RootRunner(remote, Privilege.detect(remote, p.getProperty("sudo_password")))
    val result = root.runScript(Bundle.bytes("lib/marker.sh") + Bundle.bytes("inspect.sh"), emptyList(), deadlineMs = 60_000)
    val report = LineCodec.parse(result.stdoutText) as? Report.Complete ?: return 4.also { println("inspection: report broken") }
    val inventory = Inventory.from(report.lines)
    val classification = Classifier.classify(inventory)
    val install = classification.installations.firstOrNull { it.name == name } ?: return 2.also { println("no installation $name on this box") }
    val listing = com.csqtt.deploy.run.ForeignRemoval.listing(install, classification, inventory, purge)
        ?: return 2.also { println("$name is not a stock systemd install") }
    listing.will.forEach { println("will: $it") }
    val runner = Runner(remote, root)
    val coordinator = coordinator(target)
    val runId = Runner.newRunId()
    val before = coordinator.state
    coordinator.move(DeployState.Running(runId))
    when (val outcome = runner.start(runId, com.csqtt.deploy.run.ForeignRemoval.plan(install, inventory, listing))) {
        is StartOutcome.Started -> {}
        is StartOutcome.Busy -> {
            coordinator.move(if (outcome.activeRunId.isNotEmpty()) DeployState.Running(outcome.activeRunId) else before)
            println("busy: run ${outcome.activeRunId} holds the box (attach follows it)")
            return 8
        }
        is StartOutcome.Refused -> {
            coordinator.move(before)
            println("refused: ${outcome.reason}")
            return 7
        }
    }
    return follow(runner, coordinator, runId, before)
}

/**
 * «Обновить» a stock systemd install = its move into our layout (P2 B12): inspected (the disk and run-dir stops of a core run
 * first), the move proposed (our id, interface, subnet and slot; its ports), what it takes said, the run, then ours read back
 * (the same main password from the copied store, the same certificate pin) and checked from here.
 */
private fun move(session: SshSession, p: Properties, target: File, name: String, resumeOf: String? = null): Int {
    val corePath = p.prop("core") ?: return 2.also { println("the target names no core=<file>") }
    val core = Core(File(corePath).readBytes(), p.prop("core_version") ?: return 2.also { println("the target names no core_version=") })
    val remote = session.asRemote()
    val root = RootRunner(remote, Privilege.detect(remote, p.getProperty("sudo_password")))
    val runner = Runner(remote, root)
    val plan: RunPlan
    val id: String
    if (resumeOf != null) {
        // «Продолжить» a move a reboot or a kill cut off: the same plan (its id, its stock objects), resent with resume; its
        // credentials are only the fallbacks of a stock env that had none, so new ones serve
        if (runner.status().none { it.id == resumeOf && it.interrupted && it.action == "create" }) {
            return 2.also { println("run $resumeOf is not a move the box lists as cut off: nothing to continue") }
        }
        val kept = runner.keptPlan(resumeOf)?.lines()?.filter { '=' in it }?.associate { it.substringBefore('=') to it.substringAfter('=') }
            ?: return 2.also { println("no plan kept for run $resumeOf on the box") }
        if (kept["move_role"].isNullOrEmpty()) return 2.also { println("run $resumeOf was no move") }
        id = kept["id"] ?: return 2.also { println("run $resumeOf kept no id") }
        val credentials = Credentials.generate()
        plan = RunPlan("create", kept - "action" + mapOf("resume" to "1", "resume_of" to resumeOf),
            linkedMapOf("main_password" to credentials.mainPassword, "web_pass" to credentials.webPass, "secret_path" to credentials.secretPath),
            core.bytes)
        println("will: «Продолжить» the move into sqc-$id from run $resumeOf")
    } else {
        val result = root.runScript(Bundle.bytes("lib/marker.sh") + Bundle.bytes("inspect.sh"), emptyList(), deadlineMs = 60_000)
        val report = LineCodec.parse(result.stdoutText) as? Report.Complete ?: return 4.also { println("inspection: report broken") }
        if (coreRunStopped(report)) return 6
        val inventory = Inventory.from(report.lines)
        val classification = Classifier.classify(inventory)
        val install = classification.installations.firstOrNull { it.name == name } ?: return 2.also { println("no installation $name on this box") }
        val proposal = try {
            Proposer.proposeMove(install, classification, inventory)
        } catch (refused: ProposalRefused) {
            return 2.also { println("refused: ${refused.reason}") }
        }
        plan = com.csqtt.deploy.run.MoveRun.plan(install, classification, inventory, proposal,
            CreateRun.publicAddress(Preflight.from(report.lines), p.prop("host").orEmpty()), core, Credentials.generate())
            ?: return 2.also { println("$name is not a stock systemd install to move") }
        id = proposal.id
        println("will: $name moves into ${proposal.instance}: ${proposal.iface} ${proposal.subnet} udp=${proposal.udpPort} " +
            "web=${proposal.webPort} slot=${proposal.slot}")
        com.csqtt.deploy.run.ForeignRemoval.listing(install, classification, inventory, purge = false)?.will?.forEach { println("will then: $it") }
    }
    val sshHost = p.prop("host").orEmpty()
    val coordinator = coordinator(target)
    val runId = Runner.newRunId()
    val before = coordinator.state
    coordinator.move(DeployState.Running(runId))
    when (val outcome = runner.start(runId, plan)) {
        is StartOutcome.Started -> {}
        is StartOutcome.Busy -> {
            coordinator.move(if (outcome.activeRunId.isNotEmpty()) DeployState.Running(outcome.activeRunId) else before)
            println("busy: run ${outcome.activeRunId} holds the box (attach follows it)")
            return 8
        }
        is StartOutcome.Refused -> {
            coordinator.move(before)
            println("refused: ${outcome.reason}")
            return 7
        }
    }
    val code = follow(runner, coordinator, runId, before)
    if (code != 0) return code
    return readBack(root, id, sshHost, null)
}
