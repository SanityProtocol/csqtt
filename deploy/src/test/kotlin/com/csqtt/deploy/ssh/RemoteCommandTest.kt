// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteCommandTest {
    @Test
    fun theCommandsTheEngineSendsAreTheirWords() {
        assertEquals("/bin/sh -s", RemoteCommand.shellFromStdin().toString())
        assertEquals(
            "/bin/sh /var/lib/sanityprotocol/csqtt-deploy/r20261124-ab12/run.sh tail r20261124-ab12 4096",
            RemoteCommand.shellScript("/var/lib/sanityprotocol/csqtt-deploy/r20261124-ab12/run.sh", "tail", "r20261124-ab12", "4096")
                .toString(),
        )
        assertEquals("sudo -n true", RemoteCommand.of("sudo", "-n", "true").toString())
    }

    @Test
    fun anythingAShellCouldReadDifferentlyIsRefused() {
        // quotes, spaces, expansion, globs, separators, redirections, history, comments, home, newlines: each would mean
        // something else in fish, csh or zsh than in sh, or let a value become syntax
        val unsafe = listOf(
            "", "a b", "'x'", "\"x\"", "\$HOME", "\${x}", "`id`", "\$(id)", "*", "a?", "[ab]", "~", "a;b", "a|b", "a&b",
            "a>b", "a<b", "(x)", "{a,b}", "!x", "#x", "a\nb", "a\tb", "\\x", "пароль",
        )
        for (word in unsafe) {
            assertThrows("\"$word\" must be refused", IllegalArgumentException::class.java) { RemoteCommand.of("/bin/sh", word) }
        }
        assertThrows(IllegalArgumentException::class.java) { RemoteCommand.of() }
    }
}
