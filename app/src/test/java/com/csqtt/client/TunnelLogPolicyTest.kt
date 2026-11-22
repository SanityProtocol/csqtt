package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelLogPolicyTest {
    @Test
    fun removesStatusStickersFromVisibleLogs() {
        assertEquals(
            "VPN TUN: разрешение Android VPN было отозвано",
            withoutLogStickers("❌ VPN TUN: разрешение Android VPN было отозвано"),
        )
        assertEquals("Поток готов ✓", withoutLogStickers("Поток готов ✓"))
        assertEquals("✗ Ошибка VPN", withoutLogStickers("✗ Ошибка VPN"))
        assertEquals("Сервис запущен", withoutLogStickers("[✅] Сервис запущен"))
        assertEquals("[СЕТЬ] Переподключение", withoutLogStickers("[СЕТЬ] ⚠ Переподключение"))
    }

    @Test
    fun hidesOnlyStunCodesNotTheDigitsElsewhere() {
        // M-88: the codes as the core prints them are recovery
        assertTrue(TunnelLogPolicy.isInternalRecovery("[ВОРКЕР #3] Ошибка: TURN Allocate failed: STUN error 486"))
        assertTrue(TunnelLogPolicy.isInternalRecovery("turn refresh failed: stun error 437"))
        // "486" / "437" in a port, a byte count, an address or a word is not
        assertFalse(TunnelLogPolicy.isInternalRecovery("[СЕТЬ] Подключение к 203.0.113.7:48612"))
        assertFalse(TunnelLogPolicy.isInternalRecovery("[ВОРКЕР #2] Ошибка: connection refused 10.4.37.1:4370"))
        assertFalse(TunnelLogPolicy.isInternalRecovery("[СЕТЬ] Активных: 10 · трафик 486,37 МБ"))
        assertFalse(TunnelLogPolicy.isInternalRecovery("STUN error 4861"))
        // a stream that went down shows, whatever digits or words it carries
        assertFalse(TunnelLogPolicy.isInternalRecovery("[TURN][DOWN] Allocation истекает на 10.0.4.37:4370, timeout"))
        assertTrue(TunnelLogPolicy.isTurnStreamFailure("[TURN][DOWN] Allocation истекает на 10.0.4.37:4370, timeout"))
    }

    @Test
    fun hidesEveryGetconfDiagnostic() {
        assertTrue(TunnelLogPolicy.isInternalRecovery("[GETCONF] Sending 72 bytes"))
        assertTrue(TunnelLogPolicy.isInternalRecovery("[ВОРКЕР #8] GETCONF timeout 750ms"))
        assertTrue(TunnelLogPolicy.isInternalRecovery("GETCONF чтение ответа конфига: timeout"))
        assertFalse(
            TunnelLogPolicy.isInternalRecovery(
                "GETCONF: FATAL_AUTH: неверный пароль подключения"
            )
        )
        assertFalse(
            TunnelLogPolicy.isInternalRecovery(
                "GETCONF: FATAL_PROTOCOL: клиент и сервер используют разные версии протокола"
            )
        )
    }

    @Test
    fun hidesTurnRetriesAndInternalRecovery() {
        assertTrue(
            TunnelLogPolicy.isInternalRecovery(
                "[TURN][RETRY] Ошибка обновления permission: timeout"
            )
        )
        assertTrue(
            TunnelLogPolicy.isInternalRecovery(
                "[TURN][RETRY] Ошибка транзакции ChannelBind: timeout"
            )
        )
        assertTrue(
            TunnelLogPolicy.isInternalRecovery(
                "[TURN][RETRY] Квота Allocate у креда #1; STUN error 486"
            )
        )
        assertTrue(
            TunnelLogPolicy.isInternalRecovery(
                "[TURN][RETRY] Allocation mismatch у креда #1; STUN error 437"
            )
        )
        assertFalse(
            TunnelLogPolicy.isInternalRecovery(
                "[TURN][DOWN] Permission истекает, переподнимаем поток"
            )
        )
        assertTrue(
            TunnelLogPolicy.isInternalRecovery(
                "[ВОРКЕР #17] PEER_LIVENESS_TIMEOUT: 8s без валидных пакетов"
            )
        )
        assertTrue(
            TunnelLogPolicy.isInternalRecovery(
                "[ВОРКЕР #2] [TURN][RETRY] Попытка 46: TURN liveness probe failed"
            )
        )
        assertTrue(
            TunnelLogPolicy.isInternalRecovery(
                "[ВОРКЕР #2] [СЕТЬ][RETRY] Временная ошибка сокета"
            )
        )
        assertTrue(
            TunnelLogPolicy.isInternalRecovery(
                "[ВОРКЕР #1] Ошибка (попытка 1): turn allocate: transaction timeout"
            )
        )
    }

    @Test
    fun surfacesOnlyTurnFailuresThatEndAStream() {
        assertTrue(
            TunnelLogPolicy.isTurnStreamFailure(
                "[TURN][DOWN] Allocation истекает, переподнимаем поток"
            )
        )
        assertTrue(
            TunnelLogPolicy.isTurnStreamFailure(
                "[ВОРКЕР #4] [TURN] Ошибка allocation/кредов: socket closed"
            )
        )
        assertFalse(
            TunnelLogPolicy.isTurnStreamFailure(
                "[ГРУППА #1] [TURN] Креды обновлены, TURN urls=3"
            )
        )
    }
}
