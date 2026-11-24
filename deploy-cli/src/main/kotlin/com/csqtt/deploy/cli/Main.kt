// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.cli

import com.csqtt.deploy.bundle.Bundle
import kotlin.system.exitProcess

private const val USAGE = """usage: deploy-cli <command>
  bundle-check   the bundle this build carries: each file's sha256 and size, then the bundle id (the zoo records it per cell)"""

fun main(args: Array<String>) {
    val code = when (args.toList()) {
        listOf("bundle-check") -> bundleCheck()
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
