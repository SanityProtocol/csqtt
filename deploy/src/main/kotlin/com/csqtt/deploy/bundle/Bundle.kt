// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.bundle

import java.security.MessageDigest

/** deploy-bundle/ as the engine carries it: the scripts it uploads to a VPS, shipped as resources of this module. */
object Bundle {
    /** Every file of deploy-bundle/. BundleTest holds this list equal to the directory, so none is left behind. */
    val FILES: List<String> = listOf("inspect.sh", "lib/authkey.sh", "lib/marker.sh")

    private const val ROOT = "com/csqtt/deploy/bundle/"

    fun bytes(name: String): ByteArray =
        Bundle::class.java.classLoader.getResourceAsStream(ROOT + name)?.use { it.readBytes() }
            ?: error("the build carries no bundle file $name")

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** One id for the whole bundle, from each file's name and sha256 in order: the zoo records which bundle each cell ran. */
    fun id(): String = sha256(FILES.joinToString("") { "$it ${sha256(bytes(it))}\n" }.toByteArray())
}
