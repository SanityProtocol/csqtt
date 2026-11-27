package com.csqtt.client

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class SubscriptionRefreshOutcome(
    val success: Boolean,
    val message: String,
    val title: String = "",
    val nodes: Int = 0,
    val skipped: Int = 0,
    /** The nodes that went into a profile, new or its own. */
    val written: Int = 0,
    /** The certificate the fetch turned away, when that is why it failed (P3 B2). */
    val refused: SubscriptionCertRecord? = null,
)

/** Manual and scheduled subscription refreshes share one lock per URL. */
internal object SubscriptionSync {
    /** How often a subscription added in the app refreshes until the user picks another interval (the canvas, F-SubEdit). */
    const val NEW_AUTO_UPDATE_MINUTES = 60

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

    /**
     * [whileConnected]: the tunnel service's hourly call (M-74). The app is outside its own VPN, so the fetch goes
     * directly; what it writes reaches the tunnel at the next (re)connect, as S8's hashes do. The activity's loop keeps
     * to the idle tunnel.
     */
    /** The activity's loop refreshes only with the tunnel idle; the tunnel service's hourly call while it runs (M-74). */
    internal fun mayRefresh(tunnelActive: Boolean, whileConnected: Boolean): Boolean = whileConnected || !tunnelActive

    suspend fun refreshDue(store: SettingsStore, now: Long = System.currentTimeMillis(), whileConnected: Boolean = false) {
        val tunnelActive = TunnelManager.running.value || TunnelManager.starting.value ||
            TunnelManager.stopping.value || TunnelManager.autoPausedForWifi.value
        if (!mayRefresh(tunnelActive, whileConnected)) return
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
                } else if (outcome.skipped > 0) {
                    // M-70: nodes that found no profile are said, with the reason, instead of vanishing
                    TunnelManager.updateLog(
                        "subscription_refresh_${meta.url.hashCode()}",
                        "[ПОДПИСКА] «${current.title.ifBlank { "Подписка" }}»: ${outcome.message}",
                        60,
                    )
                }
            }
        }
    }

    /**
     * The URL a subscription is kept under, and how its server is trusted. The key comes first, and the record is the
     * one under that key: a link typed again is judged against the certificate it was trusted with, never trusted
     * afresh. The key is the stored one whenever the link is a subscription the store already has — under the link's
     * own spelling, or under another spelling of the same address (without its scheme, `http://` for `https://`, a host
     * in other letter case: [subscriptionAddress]) — so one subscription is never kept twice, and its other spelling
     * is no way around its certificate. Marked by the user ([allowHttp]: the import sheet's switch, an http:// link, or
     * the record's own mark), the server is fetched over HTTPS all the same and trusted by its pinned certificate
     * (M-171).
     */
    internal fun trustFor(metas: Map<String, SubscriptionMeta>, rawUrl: String, allowHttp: Boolean): Pair<String, SubscriptionTrust> {
        val typed = rawUrl.trim()
        val spelled = normalizeSubscriptionUrl(typed, allowHttp || typed.startsWith("http://", ignoreCase = true))
        val address = subscriptionAddress(spelled)
        val url = spelled.takeIf { it in metas }
            ?: metas.keys.firstOrNull { address != null && subscriptionAddress(it) == address }
            ?: spelled
        val meta = metas[url]
        val pinned = allowHttp || meta?.allowHttp == true || url.startsWith("http://", ignoreCase = true)
        return url to SubscriptionTrust(pinned, meta?.trusted)
    }

    /**
     * The import sheet's «Загрузить» (P3 B2): what the subscription holds, for the user to see before anything is
     * written. Nothing is stored. The URL the result belongs to comes back with it: [importPreviewed] takes that one.
     */
    suspend fun preview(store: SettingsStore, rawUrl: String, allowHttp: Boolean): Pair<String, SubscriptionFetchResult> {
        val (url, trust) = trustFor(store.subscriptionMeta.first(), rawUrl, allowHttp)
        return url to SubscriptionImporter.fetch(url, trust)
    }

    /**
     * The import sheet's «Импортировать»: what [preview] showed is written, under the lock a refresh of the same URL
     * takes. A subscription that is new refreshes by itself from then on, once an hour, until the user says otherwise.
     */
    suspend fun importPreviewed(
        store: SettingsStore,
        url: String,
        preview: SubscriptionPreview,
        cert: SubscriptionCertRecord?,
        allowHttp: Boolean,
    ): SubscriptionRefreshOutcome = lockFor(url).withLock {
        val metas = store.subscriptionMeta.first()
        val known = metas[url]
        val outcome = written(store, url, known, preview, cert, trustFor(metas, url, allowHttp).second.pinned)
        if (known == null) {
            // nothing of it went into a profile (every node's server is a profile already, or none is free): there is
            // no subscription to keep, and nothing for a refresh to come back to
            if (outcome.written == 0) {
                store.deleteSubscriptionMeta(url)
                return@withLock outcome
            }
            store.saveSubscriptionAutoUpdate(url, NEW_AUTO_UPDATE_MINUTES)
        }
        recordOutcome(store, url, outcome.title, outcome)
        outcome
    }

    /**
     * «Доверять новому» (P3 B2): the certificate the last refresh turned away, which the user was shown as [sha256],
     * becomes the trusted one, and the subscription is refreshed with it. Null when that certificate is no longer the
     * one waiting (another refresh came between): nothing is trusted.
     */
    suspend fun trustAndRefresh(store: SettingsStore, url: String, sha256: String): SubscriptionRefreshOutcome? =
        lockFor(url).withLock {
            if (store.trustRefusedCertificate(url, sha256)) performRefresh(store, url) else null
        }

    private suspend fun performRefresh(
        store: SettingsStore,
        url: String,
    ): SubscriptionRefreshOutcome {
        val metas = store.subscriptionMeta.first()
        val (normalized, trust) = trustFor(metas, url, allowHttp = false)
        val meta = metas[normalized]
        store.markSubscriptionAttempt(normalized)
        val outcome = when (val result = SubscriptionImporter.fetch(normalized, trust)) {
            is SubscriptionFetchResult.Success -> written(store, normalized, meta, result.preview, result.cert, trust.pinned)
            is SubscriptionFetchResult.Failure -> SubscriptionRefreshOutcome(false, result.message, refused = result.refused)
        }
        recordOutcome(store, normalized, meta?.title.orEmpty(), outcome)
        return outcome
    }

    /** A fetched subscription into the store: its certificate recorded, its nodes into their profiles, its time marked. */
    private suspend fun written(
        store: SettingsStore,
        url: String,
        meta: SubscriptionMeta?,
        preview: SubscriptionPreview,
        cert: SubscriptionCertRecord?,
        pinned: Boolean,
    ): SubscriptionRefreshOutcome {
        cert?.let { store.recordSubscriptionCertificate(url, it) }
        val summary = SubscriptionImporter.importNodes(store = store, rawUrl = url, preview = preview, allowHttp = pinned)
        store.markSubscriptionUpdated(url, title = preview.title)
        return SubscriptionRefreshOutcome(
            success = true,
            message = summary.message(),
            title = meta?.title?.takeIf { it.isNotBlank() } ?: preview.title,
            nodes = preview.nodes.size,
            skipped = summary.skipped,
            written = summary.total,
        )
    }

    /**
     * B5h2 (U3, rule 10): the outcome is kept for «Подключение», not only logged; a new failure is announced while the app
     * is not in front, and a success withdraws it. Masked: a server's message can carry anything.
     */
    private suspend fun recordOutcome(store: SettingsStore, url: String, title: String, outcome: SubscriptionRefreshOutcome) {
        val (error, skipped) = subscriptionOutcomeTexts(outcome.success, outcome.message, outcome.skipped)
        val context = store.appContext
        val id = CsqttConstants.Notifications.SUBSCRIPTION_NOTIFICATION_ID
        // one notice per subscription; its URL may carry an access token, so the tag is a hash of it
        val tag = "subscription:${url.hashCode()}"
        if (store.recordSubscriptionOutcome(url, error, skipped, outcome.refused)) {
            BackgroundNotice.post(context, id, "Подписка «${title.ifBlank { "Подписка" }}» не обновилась", error, tag)
        } else if (outcome.success) {
            BackgroundNotice.cancel(context, id, tag)
        }
    }
}
