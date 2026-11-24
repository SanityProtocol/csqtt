// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.run

import com.csqtt.deploy.inspect.Inventory
import com.csqtt.deploy.inspect.Preflight

/**
 * Docker mode's root filesystem (P2 B13a; APP-PLAN §6.11 rule 1; P2-PLAN §1.8): ci/build-rootfs.sh's output per architecture,
 * pinned here by its sha256 (the build is reproducible: CI builds the three and fails on a mismatch with rootfs.pins). The box
 * imports it and builds our image from it with no network; nothing is pulled from a registry or a package mirror.
 */
object Rootfs {
    /** One architecture's: the sha256 of csqtt-rootfs-<arch>.tar.gz and its size. */
    data class Pin(val arch: String, val sha256: String, val bytes: Long) {
        val file: String get() = "csqtt-rootfs-$arch.tar.gz"

        /** The image the box imports it as: shared by our instances, never pulled. */
        val tag: String get() = "sanity-csqtt-rootfs:" + sha256.take(8)
    }

    private val pins: Map<String, Pin> by lazy {
        val text = checkNotNull(Rootfs::class.java.getResource("rootfs.pins")) { "rootfs.pins is missing" }.readText()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("alpine ") }
            .map { it.split(Regex("\\s+")) }.associate { (arch, sha, bytes) -> arch to Pin(arch, sha, bytes.toLong()) }
    }

    /** The box's architecture as the cores and the pins name it, from `uname -m`; null: none we build. */
    fun archOf(machine: String): String? = when (machine) {
        "x86_64" -> "amd64"
        "aarch64" -> "arm64"
        "armv7l" -> "armv7"
        else -> null
    }

    fun pin(arch: String): Pin? = pins[arch]

    /** Where the box fetches it: the release of the carried core, where it is uploaded by hand beside the cores. */
    fun url(version: String, pin: Pin): String = "https://github.com/SanityProtocol/csqtt/releases/download/v$version/${pin.file}"

    /** The host whose reach the inspection checks before a Docker create (inspect.sh's arguments). */
    const val HOST = "github.com"
}

/** A Docker create's root filesystem: the box's architecture's pin, and where the box gets it. */
class RootfsChoice(val pin: Rootfs.Pin, val source: DockerMode.Source)

object DockerMode {
    /**
     * Why Docker mode is not offered on this box, or null when it is (APP-PLAN §6.11 rule 7: an unsupported flavour stops before
     * any change, with its reason, and systemd is offered): Docker absent, snap's or podman's or rootless, or an architecture we
     * build no root filesystem for.
     */
    fun refusal(preflight: Preflight): String? {
        val docker = preflight.facts("docker")
        if (docker["bin"].isEmpty()) return "Docker не установлен"
        if (docker["rootless"] == "1") return "Docker без прав root (rootless) не подходит: серверу нужен NET_ADMIN на сервере"
        when (docker["flavour"]) {
            "snap" -> return "Docker из snap не подходит"
            "podman" -> return "Podman вместо Docker не подходит"
        }
        val arch = Rootfs.archOf(preflight.facts("arch")["machine"]) ?: return "для этой архитектуры нет образа"
        return if (Rootfs.pin(arch) == null) "для этой архитектуры нет образа" else null
    }

    /** Where the box gets the root filesystem (C3): it has the image already, it fetches it, or the client brings its copy. */
    sealed class Source {
        object Present : Source()

        data class Fetch(val url: String) : Source()

        class Upload(val bytes: ByteArray) : Source()
    }

    /**
     * C3's order, by the facts: the image already on the box (inspect lists our images while Docker runs) → nothing moves; the
     * box reaches [Rootfs.HOST] (inspect's reach check) → it fetches from [url]; else the client's [copy] (its sha256 the pin's)
     * is uploaded; null: neither, and Docker mode is not offered. [fetch] false: the client brings it whatever the reach says
     * (a fetch that failed before: the run's own advice).
     */
    fun source(pin: Rootfs.Pin, url: String, preflight: Preflight, inv: Inventory, copy: ByteArray?, fetch: Boolean = true): Source? {
        if (inv.images.any { it.ref == pin.tag }) return Source.Present
        val reached = preflight.reach.any { it["host"] == Rootfs.HOST && it["dns"] == "1" && it["https"] == "ok" }
        if (fetch && reached) return Source.Fetch(url)
        if (copy != null && copy.size.toLong() == pin.bytes && sha256Hex(copy) == pin.sha256) return Source.Upload(copy)
        return null
    }

    /** Where the root filesystem comes from, for a «Будет» (a create's, an update's, a restore's). */
    fun sourceWill(choice: RootfsChoice): String = when (val source = choice.source) {
        is Source.Present -> "Файловая система образа уже на сервере"
        is Source.Fetch -> "Сервер скачает файловую систему образа: ${source.url}"
        is Source.Upload -> "Файловая система образа уйдёт на сервер отсюда (${choice.pin.bytes / 1024} КБ)"
    }

    /** What a Docker create changes beyond a create's host changes, for its «Будет». */
    fun hostChanges(preflight: Preflight): List<String> {
        val docker = preflight.facts("docker")
        return listOfNotNull(
            if (docker["active"] != "active") "Запустит Docker (а с ним контейнеры, которым положено работать)" else null,
            if (docker["enabled"] != "enabled") "Включит запуск Docker при загрузке, иначе после перезагрузки сервер не поднимется" else null,
            "Соберёт образ sanity-csqtt из нашего ядра и закреплённой файловой системы Alpine, без скачивания из реестра",
            // (in Docker the server cannot set them itself: the run sets and keeps them, as its server would in systemd mode)
            "Закрепит в /etc/sysctl.d/99-sanity-csqtt.conf настройки сети, которые сервер задаёт сам вне Docker: BBR, fq, мягкий rp_filter",
        )
    }
}
