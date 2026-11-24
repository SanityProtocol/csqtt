// SPDX-FileCopyrightText: 2026 SanityProtocol
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.csqtt.deploy.run

import com.csqtt.deploy.inspect.Preflight
import java.util.Base64

/**
 * «Сертификат» (P2 B14; P2-PLAN B14; M-119, M-135): the panel's certificate from Let's Encrypt for an instance of ours, an IP
 * certificate on the short-lived profile, renewed by our timer, the server told to reload it (SIGUSR1: no client is cut). A run
 * of its own after a create, a move or a restore ended ok, so a cut in it never undoes a verified instance; where none can be
 * had the server keeps its own certificate and the run ends ok with the cause.
 */
object LeRun {
    /** certbot as pinned in our venv (run.sh's LE_CERTBOT): `--ip-address` came in 5.3; 5.x needs Python 3.10. */
    const val CERTBOT = "5.10.0"

    /**
     * Where the certificate comes from: [url] the ACME directory (empty: Let's Encrypt's own), [caPem] its CA when it is no
     * public one, [pipFindLinks] where pip finds certbot when PyPI is out of reach (the zoo's Pebble and wheelhouse).
     */
    class Acme(val url: String = "", val caPem: ByteArray? = null, val pipFindLinks: String = "")

    fun plan(id: String, publicIp: String, acme: Acme = Acme()): RunPlan = RunPlan("le", linkedMapOf(
        "id" to id, "public_ip" to publicIp, "acme_url" to acme.url,
        "acme_ca" to acme.caPem?.let { Base64.getEncoder().encodeToString(it) }.orEmpty(),
        "pip_find_links" to acme.pipFindLinks,
    ))

    /** What the run changes, for its «Будет», and why it would get none where the facts say so already. */
    fun will(preflight: Preflight, publicIp: String): List<String> {
        val le = preflight.facts("le")
        val python = le["python"]
        val keep = "панель останется на своём сертификате"
        if (python.isEmpty()) return listOf("Сертификат Let's Encrypt не получить: на сервере нет python3; $keep")
        val (major, minor) = python.split('.').map { it.toIntOrNull() ?: 0 }.let { (it.getOrNull(0) ?: 0) to (it.getOrNull(1) ?: 0) }
        if (major < 3 || major == 3 && minor < 10) {
            return listOf("Сертификат Let's Encrypt не получить: Python $python старше 3.10, который нужен certbot $CERTBOT; $keep")
        }
        val holder = preflight.facts("port80")["holder"]
        if (holder.isNotEmpty()) return listOf("Сертификат Let's Encrypt не получить, пока TCP 80 занят ($holder): $keep")
        return listOfNotNull(
            // (ours there already stays, whatever its version: the pin is for a new install, which alone needs venv)
            if (le["certbot"].isEmpty() && le["venv"] != "1") "Установит python3-venv" else null,
            if (le["certbot"].isEmpty()) "Поставит certbot $CERTBOT с PyPI в /opt/sanityprotocol/certbot" else null,
            "Получит сертификат Let's Encrypt на $publicIp (короткоживущий, около 6 дней): на время проверки откроет TCP 80",
            "Таймер sanity-csqtt-le.timer будет продлевать его дважды в день; сервер подхватит новый без перезапуска",
        )
    }
}
