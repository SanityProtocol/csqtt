// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
//
// The only strings deploy v3 hands to a VPS's login shell (CSQTT-APP-PLAN §6.3 "The commands on the wire are paths, never
// shell syntax"; DE-T8). Today's deploy sends `env A=… bash -c '<quoted script>'` through whatever shell the user has; fish and
// csh parse that differently, and the quoting is where secrets met argv. Here a command is words drawn from a set no shell
// treats specially, so sh, bash, dash, zsh, fish and csh all read the same words. Scripts and secrets travel on stdin.
package com.csqtt.deploy.ssh

/** One command: its words, each of [SAFE] characters only, joined by single spaces. */
class RemoteCommand private constructor(val words: List<String>) {
    /** The exec string. */
    override fun toString(): String = words.joinToString(" ")

    companion object {
        /** Letters, digits and `._/@:=+,-`: no quote, no space, no `$`, no glob, no `;|&<>()` and no newline. */
        private val SAFE = Regex("[A-Za-z0-9._/@:=+,-]+")

        fun of(vararg words: String): RemoteCommand {
            require(words.isNotEmpty()) { "an empty command" }
            for (word in words) require(SAFE.matches(word)) { "not a safe word for a remote command: \"$word\"" }
            return RemoteCommand(words.toList())
        }

        /** `/bin/sh -s`: a script on stdin, so nothing of it is ever parsed by the login shell (inspect.sh, B3). */
        fun shellFromStdin(): RemoteCommand = of("/bin/sh", "-s")

        /** `/bin/sh <script> <args…>`: a script already on the box (run.sh), run by path, so a noexec mount does not matter. */
        fun shellScript(path: String, vararg args: String): RemoteCommand = of("/bin/sh", path, *args)
    }
}
