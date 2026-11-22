package com.csqtt.client

object TunnelLogPolicy {
    // M-88: the core says «STUN error 486» / «stun error 437» (Allocation Quota Reached, Allocation Mismatch); the digits
    // alone are as often a port, a byte count or an address, and hid those lines
    private val RECOVERY_STUN_CODE = Regex("\\bstun error (?:486|437)\\b", RegexOption.IGNORE_CASE)

    fun isInternalRecovery(line: String): Boolean {
        if (line.contains("FATAL_", ignoreCase = true)) return false
        // a stream that went down is the user's to see, whatever else the line says (#19's rule)
        if (line.contains("[TURN][DOWN]", ignoreCase = true)) return false
        return line.contains("GETCONF", ignoreCase = true) ||
            line.contains("[СЕТЬ][RETRY]", ignoreCase = true) ||
            line.contains("[TURN][RETRY]", ignoreCase = true) ||
            line.contains("[HANDSHAKE][RETRY]", ignoreCase = true) ||
            line.contains("PEER_LIVENESS_TIMEOUT", ignoreCase = true) ||
            RECOVERY_STUN_CODE.containsMatchIn(line) ||
            (line.contains("turn", ignoreCase = true) && line.contains("timeout", ignoreCase = true)) ||
            (line.contains("allocation", ignoreCase = true) && line.contains("quota", ignoreCase = true)) ||
            (line.contains("allocation", ignoreCase = true) && line.contains("mismatch", ignoreCase = true)) ||
            (line.contains("[КРЕД", ignoreCase = true) && line.contains("Повторяем через", ignoreCase = true))
    }

    fun isTurnStreamFailure(line: String): Boolean {
        if (isInternalRecovery(line)) return false
        return line.contains("[TURN][DOWN]", ignoreCase = true) ||
            (
                line.contains("[ВОРКЕР", ignoreCase = true) &&
                    line.contains("[TURN]", ignoreCase = true) &&
                    (
                        line.contains("ошибка", ignoreCase = true) ||
                            line.contains("failed", ignoreCase = true) ||
                            line.contains("exhausted", ignoreCase = true)
                        )
                )
    }
}
