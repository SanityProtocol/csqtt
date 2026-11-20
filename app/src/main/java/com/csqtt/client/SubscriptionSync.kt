package com.csqtt.client

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class SubscriptionRefreshOutcome(
    val success: Boolean,
    val message: String,
    val title: String = "",
    val nodes: Int = 0,
)

/** Manual and scheduled subscription refreshes share one lock per URL. */
internal object SubscriptionSync {
    private val locks = mutableMapOf<String, Mutex>()

    private fun lockFor(url: String): Mutex = synchronized(locks) {
        locks.getOrPut(url) { Mutex() }
    }

    suspend fun refresh(store: SettingsStore, rawUrl: String): SubscriptionRefreshOutcome {
        val url = rawUrl.trim()
        if (url.isEmpty()) return SubscriptionRefreshOutcome(false, "Укажите ссылку подписки")
        return lockFor(url).withLock {
            performRefresh(store, url)
        }
    }

    suspend fun refreshDue(store: SettingsStore, now: Long = System.currentTimeMillis()) {
        if (TunnelManager.running.value ||
            TunnelManager.starting.value ||
            TunnelManager.stopping.value ||
            TunnelManager.autoPausedForWifi.value
        ) {
            return
        }
        val due = store.subscriptionMeta.first().values
            .filter { it.needsUpdate(now) }
            .sortedBy { maxOf(it.lastUpdatedAt, it.lastAttemptAt) }
        for (meta in due) {
            lockFor(meta.url).withLock {
                val current = store.subscriptionMeta.first()[meta.url] ?: return@withLock
                if (!current.needsUpdate(System.currentTimeMillis())) return@withLock
                val outcome = performRefresh(store, meta.url)
                // classic has no subscription screen: a refresh that fails is said in the log, by title (a subscription URL
                // can carry its access token, so it is never logged)
                if (!outcome.success) {
                    TunnelManager.updateLog(
                        "subscription_refresh_${meta.url.hashCode()}",
                        "[ПОДПИСКА] «${current.title.ifBlank { "Подписка" }}» не обновлена: ${outcome.message}",
                        99,
                        true,
                    )
                }
            }
        }
    }

    private suspend fun performRefresh(
        store: SettingsStore,
        url: String,
    ): SubscriptionRefreshOutcome {
        val meta = store.subscriptionMeta.first()[url]
        // marked by the user (V2's «Скачивать по HTTP», or an http:// URL they typed): fetched over HTTPS all the same,
        // its server trusted by its pinned certificate (M-171)
        val pinned = meta?.allowHttp == true || url.startsWith("http://", ignoreCase = true)
        val normalized = normalizeSubscriptionUrl(url, pinned)
        store.markSubscriptionAttempt(normalized)
        val record = meta?.certSha256?.takeIf { it.isNotBlank() }
            ?.let {
                SubscriptionCertRecord(
                    sha256 = it,
                    notAfterMs = meta.certNotAfter,
                    kind = CertKind.fromStored(meta.certKind),
                    names = meta.certNames.split(',').map(String::trim).filter(String::isNotEmpty).toSet(),
                )
            }
        return when (val result = SubscriptionImporter.fetch(normalized, SubscriptionTrust(pinned, record))) {
            is SubscriptionFetchResult.Success -> {
                result.cert?.let { store.recordSubscriptionCertificate(normalized, it) }
                val summary = SubscriptionImporter.importNodes(
                    store = store,
                    rawUrl = normalized,
                    preview = result.preview,
                    allowHttp = pinned,
                )
                store.markSubscriptionUpdated(normalized, title = result.preview.title)
                SubscriptionRefreshOutcome(
                    success = true,
                    message = summary.message(),
                    title = meta?.title?.takeIf { it.isNotBlank() } ?: result.preview.title,
                    nodes = result.preview.nodes.size,
                )
            }
            is SubscriptionFetchResult.Failure ->
                SubscriptionRefreshOutcome(false, result.message)
        }
    }
}
