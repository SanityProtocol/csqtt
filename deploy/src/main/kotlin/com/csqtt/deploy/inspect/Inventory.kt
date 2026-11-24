// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//
// What inspect.sh saw of the installations on a VPS (CSQTT-APP-PLAN §6.4b; server-footprints.md §3; P2 B4): the five views as
// reported, typed, before the classifier (B5) joins them into installs. Nothing here decides; a field the box did not report
// is empty, never guessed.
package com.csqtt.deploy.inspect

import com.csqtt.deploy.wire.Line

/** The server's own flags as a process, unit or container carried them: `--flag value`, `--flag=value`, or a bare `--flag`. */
class ServerFlags(val values: Map<String, String?>) {
    operator fun get(flag: String): String? = values[flag]

    fun has(flag: String): Boolean = flag in values

    val configDir: String? get() = values["--config-dir"]

    /** The UDP port of `--listen <addr>:<port>`. */
    val udpPort: Int? get() = values["--listen"]?.substringAfterLast(':')?.toIntOrNull()

    val webPort: Int? get() = values["--web-port"]?.toIntOrNull()

    companion object {
        fun parse(text: String): ServerFlags {
            val words = text.split(' ').filter { it.isNotEmpty() }
            val values = linkedMapOf<String, String?>()
            var i = 0
            while (i < words.size) {
                val word = words[i]
                when {
                    !word.startsWith("--") -> {}
                    '=' in word -> values[word.substringBefore('=')] = word.substringAfter('=')
                    i + 1 < words.size && !words[i + 1].startsWith("--") -> values[word] = words[++i]
                    else -> values[word] = null
                }
                i++
            }
            return ServerFlags(values)
        }
    }
}

data class Proc(val pid: Int, val exe: String, val deleted: Boolean, val comm: String, val unit: String, val container: String,
    val tuns: List<String>, val sha: String, val flags: ServerFlags)

/**
 * One distinct binary, by its sha256, with the strings that name its build (server-footprints.md §2). [path] is the first
 * place it was seen ([inContainer]: inside a container's image); [Inventory.bins] has every host path.
 */
data class Exe(val sha: String, val path: String, val size: Long?, val label: String, val caption: Boolean, val startup: String,
    val swgFlags: Boolean, val mrtt: Boolean, val inContainer: Boolean = false)

data class UnitView(val id: String, val load: String, val active: String, val sub: String, val result: String, val mainStatus: Int?,
    val restarts: Int?, val mainPid: Int?, val fileState: String, val fragment: String, val exec: String, val execPresent: Boolean?,
    val flags: ServerFlags, val pre: List<String>, val envFiles: List<String>,
    /** The status of an ExecStartPre that failed: 20–22 network-up's, 23–24 tun-recover's (21: forwarding off, slim). */
    val preStatus: Int?)

data class Ctr(val id: String, val name: String, val image: String, val state: String, val pid: Int?, val restarts: Int?,
    val exitCode: Int?, val policy: String, val network: String, val labels: Map<String, String>, val flags: ServerFlags,
    val mounts: List<Pair<String, String>>, val runtime: String)

data class Image(val ref: String, val id: String, val runtime: String)

/**
 * A store directory. [keptFlags] and [keptRelease]: a store of ours kept by «Удалить», the flags it ran with and the release it
 * ran (`<version>-<sha8>`), from its unit.saved; null and "" otherwise (B11a review F3, F6). [linkTarget]: where [dir] points
 * when it is a symlink (a config dir moved to another disk, #5), "" otherwise. [movedTo]: a stock store a move of ours left
 * behind, the instance it went to (`sqc-<id>`, B12 review F5), "" otherwise.
 */
data class Store(val dir: String, val files: Set<String>, val lock: String, val schema: Set<String>, val envKeys: Set<String>,
    val publicIp: String, val domain: String, val keptFlags: ServerFlags? = null, val keptRelease: String = "",
    val linkTarget: String = "", val movedTo: String = "") {
    /** A live server holds this store's lock, whatever runs it. */
    val locked: Boolean get() = lock == "held"
}

data class Tun(val name: String, val state: String, val persist: Boolean)

data class Addr(val dev: String, val cidr: String)

data class Route(val dst: String, val dev: String, val table: String)

data class Listen(val proto: String, val addr: String, val port: Int?, val comm: String, val pid: Int?)

/** One netfilter line of the family: a rule (`-A …`) or a chain of its own (`:SQC-…`). */
data class FwLine(val ipVersion: Int, val table: String, val isChain: Boolean, val text: String)

data class FileView(val path: String, val type: String, val facts: Facts)

class Inventory(
    val procs: List<Proc>,
    val exes: Map<String, Exe>,
    /** Every host path a family binary was found at, to its sha256 (B4 review F2): two installs sharing a build find it each. */
    val bins: Map<String, String>,
    val units: List<UnitView>,
    val dockerRequires: List<String>,
    val dockerWants: List<String>,
    val containers: List<Ctr>,
    val images: List<Image>,
    val stores: List<Store>,
    val tuns: List<Tun>,
    val addrs: List<Addr>,
    val routes: List<Route>,
    val ipRules: List<String>,
    val listens: List<Listen>,
    val firewall: List<FwLine>,
    val nftTables: List<String>,
    val files: List<FileView>,
    /** Docker is installed and stopped: it was not asked (its socket would start it), so its containers are unknown. */
    val dockerStopped: Boolean,
) {
    /** The binary at a host path: by its bin line, or (a report from before bin lines) the exe line first seen there. */
    fun exeAt(path: String): Exe? = bins[path]?.let { exes[it] } ?: exes.values.find { it.path == path && !it.inContainer }

    companion object {
        private fun list(value: String): List<String> = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        fun from(lines: List<Line>): Inventory {
            val procs = mutableListOf<Proc>()
            val exes = linkedMapOf<String, Exe>()
            val bins = linkedMapOf<String, String>()
            val units = mutableListOf<UnitView>()
            var requires = emptyList<String>()
            var wants = emptyList<String>()
            val containers = mutableListOf<Ctr>()
            val images = mutableListOf<Image>()
            val stores = mutableListOf<Store>()
            val tuns = mutableListOf<Tun>()
            val addrs = mutableListOf<Addr>()
            val routes = mutableListOf<Route>()
            val ipRules = mutableListOf<String>()
            val listens = mutableListOf<Listen>()
            val firewall = mutableListOf<FwLine>()
            var nft = emptyList<String>()
            val files = mutableListOf<FileView>()
            var dockerStopped = false
            for (line in lines) {
                if (line.kind != "INV" || line.fields.isEmpty()) continue
                val f = Facts.of(line.fields.drop(1))
                fun int(key: String) = f[key].toIntOrNull()
                when (line.fields[0]) {
                    "proc" -> procs += Proc(int("pid") ?: continue, f["exe"], f["deleted"] == "1", f["comm"], f["unit"], f["ctr"],
                        list(f["tun"]), f["sha"], ServerFlags.parse(f["flags"]))
                    "exe" -> f["sha"].takeIf { it.isNotEmpty() }?.let { sha ->
                        exes[sha] = Exe(sha, f["path"], f.long("size"), f["label"], f["caption"] == "1", f["startup"], f["swg"] == "1",
                            f["mrtt"] == "1", f["ctr"] == "1")
                    }
                    "bin" -> if (f["path"].isNotEmpty() && f["sha"].isNotEmpty()) bins[f["path"]] = f["sha"]
                    "unit" -> units += UnitView(f["id"], f["load"], f["active"], f["sub"], f["result"], int("status"), int("restarts"),
                        int("pid"), f["file_state"], f["fragment"], f["exec"], f["exec_ok"].takeIf { it.isNotEmpty() }?.let { it == "1" },
                        ServerFlags.parse(f["flags"]), list(f["pre"]), list(f["env"]), int("pre_status"))
                    "dockerdeps" -> {
                        requires = list(f["requires"])
                        wants = list(f["wants"])
                    }
                    "ctr" -> containers += Ctr(f["id"], f["name"], f["image"], f["state"], int("pid"), int("restarts"), int("exit"),
                        f["policy"], f["net"], list(f["labels"]).associate { it.substringBefore('=') to it.substringAfter('=', "") },
                        ServerFlags.parse(f["flags"]), list(f["mounts"]).map { it.substringBefore(':') to it.substringAfter(':', "") },
                        f["runtime"])
                    "image" -> images += Image(f["ref"], f["id"], f["runtime"])
                    "store" -> stores += Store(f["dir"], list(f["files"]).toSet(), f["lock"], list(f["schema"]).toSet(),
                        list(f["env_keys"]).toSet(), f["public_ip"], f["domain"],
                        f["kept_flags"].takeIf { it.isNotEmpty() }?.let { ServerFlags.parse(it) }, f["kept_release"], f["link"], f["moved"])
                    "tun" -> tuns += Tun(f["name"], f["state"], f["persist"] == "on")
                    "addr" -> addrs += Addr(f["dev"], f["cidr"])
                    "route" -> routes += Route(f["dst"], f["dev"], f["table"])
                    "iprule" -> ipRules += f["rule"]
                    "listen" -> listens += Listen(f["proto"], f["addr"], int("port"), f["comm"], int("pid"))
                    "fw" -> firewall += FwLine(int("v") ?: 4, f["table"], f["kind"] == "chain", f["text"])
                    "nft" -> nft = list(f["tables"])
                    "file" -> files += FileView(f["path"], f["type"], f)
                    "docker" -> dockerStopped = f["daemon"] == "inactive"
                }
            }
            return Inventory(procs, exes, bins, units, requires, wants, containers, images, stores, tuns, addrs, routes, ipRules, listens,
                firewall, nft, files, dockerStopped)
        }
    }
}
