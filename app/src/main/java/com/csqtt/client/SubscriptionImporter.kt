package com.csqtt.client

import com.csqtt.client.ui.legacy.utils.normalizeCsqttPower
import com.csqtt.client.ui.legacy.utils.parseCsqttLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale

internal data class SubscriptionNode(
    /** The node's `csqtt://` link as the server served it: stored as is, never rebuilt (M-72, M-170). */
    val link: String,
    val name: String,
    val host: String,
    val port: Int,
    val password: String,
    val power: Int?,
    val hashes: List<String>,
    val subUuid: String?,
)

internal data class SubscriptionPreview(
    val title: String,
    val nodes: List<SubscriptionNode>,
    val skipped: Int,
    val isSingle: Boolean,
)

internal sealed interface SubscriptionFetchResult {
    /** [certSha256]: the subscription server's leaf certificate, when the fetch went through a pinned connection. */
    data class Success(val preview: SubscriptionPreview, val certSha256: String? = null) : SubscriptionFetchResult
    data class Failure(val message: String) : SubscriptionFetchResult
}

internal data class SubscriptionProfileWrite(
    val profile: Int,
    val link: String,
    val power: Int?,
    val nodeName: String,
    val title: String,
    val url: String,
    val subUuid: String?,
)

internal data class SubscriptionImportSummary(
    val imported: Int,
    val updated: Int,
    val skipped: Int,
    val firstProfile: Int?,
) {
    val total: Int get() = imported + updated

    fun message(): String {
        val parts = mutableListOf<String>()
        if (imported > 0) parts += "добавлено: $imported"
        if (updated > 0) parts += "обновлено: $updated"
        if (skipped > 0) parts += "пропущено: $skipped"
        return if (parts.isEmpty()) "Подписка не изменилась" else "Подписка — ${parts.joinToString(", ")}"
    }
}

/** The URL a subscription is fetched from: always HTTPS, at the same host and port as an `http://` one (M-171). */
internal fun subscriptionTransportUrl(url: String): String =
    if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

/**
 * How a subscription's own server is trusted (M-171). [pinned]: the user marked it (V2's «Скачивать по HTTP», or an
 * `http://` URL they typed) — its server is trusted by its certificate's [certSha256], pinned on the first fetch when empty.
 * Otherwise the platform's full checks.
 */
internal data class SubscriptionTrust(val pinned: Boolean = false, val certSha256: String = "")

/** The pinned certificate's check: [expected] null pins whatever is seen (trust on first use). Returns the SHA-256 hex. */
internal fun acceptPinnedLeaf(expected: String?, leafDer: ByteArray): String {
    val seen = java.security.MessageDigest.getInstance("SHA-256").digest(leafDer)
        .joinToString("") { "%02x".format(it) }
    if (expected != null && !expected.equals(seen, ignoreCase = true)) {
        throw java.security.cert.CertificateException(SubscriptionCertPin.MISMATCH)
    }
    return seen
}

/**
 * One fetch's trust for the subscription's own host and port; any other host gets the platform's checks. A certificate the
 * platform accepts is checked as for any server (its name too) and never pinned, so a CA certificate that renews keeps
 * working; only a certificate the platform refuses (a self-signed panel) is held to the pin.
 */
internal class SubscriptionCertPin(
    private val host: String,
    private val port: Int,
    private val expected: String?,
    private val platform: javax.net.ssl.X509TrustManager = platformTrustManager(),
) {
    /** The pinned certificate's SHA-256 when the last handshake went by the pin; null when the platform accepted it. */
    @Volatile
    var seen: String? = null
        private set

    /** The last handshake went by the pin, so the pin, not a CA, vouches for the name. */
    @Volatile
    var byPin: Boolean = false
        private set

    private val manager = object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {
            throw java.security.cert.CertificateException("client certificates are not used")
        }

        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("no server certificate")
            if (runCatching { platform.checkServerTrusted(chain, authType) }.isSuccess) {
                byPin = false
                return
            }
            seen = acceptPinnedLeaf(expected, leaf.encoded)
            byPin = true
        }

        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = platform.acceptedIssuers
    }

    /** The name check: the platform's, unless the pin vouched for this very certificate. */
    val hostnameVerifier = javax.net.ssl.HostnameVerifier { name, session ->
        byPin || javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(name, session)
    }

    val socketFactory: javax.net.ssl.SSLSocketFactory by lazy {
        javax.net.ssl.SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<javax.net.ssl.TrustManager>(manager), java.security.SecureRandom())
        }.socketFactory
    }

    fun covers(url: URL): Boolean =
        url.host.equals(host, ignoreCase = true) && (if (url.port == -1) url.defaultPort else url.port) == port

    companion object {
        const val MISMATCH = "subscription certificate pin mismatch"

        private fun platformTrustManager(): javax.net.ssl.X509TrustManager =
            javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as java.security.KeyStore?) }
                .trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().first()

        fun forUrl(url: String, trust: SubscriptionTrust): SubscriptionCertPin? {
            if (!trust.pinned) return null
            val parsed = runCatching { URL(url) }.getOrNull() ?: return null
            val port = if (parsed.port == -1) parsed.defaultPort else parsed.port
            return SubscriptionCertPin(parsed.host, port, trust.certSha256.trim().ifEmpty { null })
        }
    }
}

internal fun normalizeSubscriptionUrl(raw: String, allowHttp: Boolean = false): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    // the URL is the subscription's key, so a stored one is kept as it is; the fetch itself is always HTTPS
    return if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
        trimmed
    } else {
        if (allowHttp) "http://$trimmed" else "https://$trimmed"
    }
}

/**
 * Fetches and parses CSQTT subscriptions the same way focsq does:
 * JSON group/single responses first, HTML page fallback, decrypted csqtt:// links.
 */
internal object SubscriptionImporter {
    private const val MAX_BODY_BYTES = 2 * 1024 * 1024
    private const val TIMEOUT_MS = 10_000
    private const val MAX_REDIRECTS = 5

    private sealed interface BodyResult {
        data class Body(val text: String) : BodyResult
        /** [tls]: the TLS handshake failed (an `http://`-only server, or a certificate). */
        data class Failed(val message: String, val tls: Boolean = false) : BodyResult
    }

    private const val HTTP_ONLY_MESSAGE =
        "Подписка только по HTTP не поддерживается: она передаёт пароль подключения открытым текстом. Нужен HTTPS"
    private const val PIN_MISMATCH_MESSAGE =
        "Сертификат сервера подписки изменился: обновление отклонено. Если сервер сменил сертификат, добавьте подписку заново"

    private sealed interface ParseResult {
        data class Success(val preview: SubscriptionPreview) : ParseResult
        data class Rejected(val message: String) : ParseResult
        data class Invalid(val message: String) : ParseResult
    }

    suspend fun fetch(
        rawUrl: String,
        trust: SubscriptionTrust = SubscriptionTrust(),
    ): SubscriptionFetchResult = withContext(Dispatchers.IO) {
        val url = normalizeSubscriptionUrl(rawUrl, trust.pinned)
        if (url.isEmpty()) return@withContext SubscriptionFetchResult.Failure("Укажите ссылку подписки")
        val transport = subscriptionTransportUrl(url)
        val uri = runCatching { URI(transport) }.getOrNull()
        if (uri?.host.isNullOrBlank()) {
            return@withContext SubscriptionFetchResult.Failure("Некорректная ссылка подписки")
        }
        if (!isAllowedScheme(transport)) {
            return@withContext SubscriptionFetchResult.Failure("Поддерживаются только HTTPS-ссылки")
        }
        val pin = SubscriptionCertPin.forUrl(transport, trust)
        val plainHttpUrl = url.startsWith("http://", ignoreCase = true)

        var lastError = "Не удалось загрузить подписку"
        var rejectedError: String? = null
        for (candidate in candidateUrls(uri)) {
            when (val body = fetchBody(candidate, pin)) {
                is BodyResult.Failed -> lastError = if (body.tls && plainHttpUrl) HTTP_ONLY_MESSAGE else body.message
                is BodyResult.Body -> when (val parsed = parseBody(body.text)) {
                    is ParseResult.Success ->
                        return@withContext SubscriptionFetchResult.Success(parsed.preview, pin?.seen)
                    is ParseResult.Rejected -> rejectedError = parsed.message
                    is ParseResult.Invalid -> lastError = parsed.message
                }
            }
        }
        SubscriptionFetchResult.Failure(rejectedError ?: lastError)
    }

    suspend fun importNodes(
        store: SettingsStore,
        rawUrl: String,
        preview: SubscriptionPreview,
        allowHttp: Boolean = false,
    ): SubscriptionImportSummary {
        val url = normalizeSubscriptionUrl(rawUrl)
        if (url.isEmpty() || preview.nodes.isEmpty()) {
            return SubscriptionImportSummary(0, 0, preview.skipped, null)
        }

        val occupancy = store.occupiedProfiles.first()
        val subscriptions = store.profileSubscriptions.first()
        val snapshots = mutableMapOf<Int, ProfileConnectionSnapshot>()
        for (slot in CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX) {
            if (slot in occupancy) {
                snapshots[slot] = store.readProfileConnectionSettings(slot)
            }
        }

        val existingForUrl = subscriptions
            .filterValues { it.url == url }
            .keys
            .sorted()
        val byUuid = mutableMapOf<String, Int>()
        for ((slot, info) in subscriptions) {
            if (info.subUuid.isNotEmpty()) byUuid[info.subUuid] = slot
        }
        val byEndpoint = mutableMapOf<String, Int>()
        for (slot in existingForUrl) {
            snapshots[slot]?.endpointKey()?.let { byEndpoint.putIfAbsent(it, slot) }
        }
        val globalEndpoints = mutableMapOf<String, Int>()
        for ((slot, snapshot) in snapshots) {
            snapshot.endpointKey()?.let { globalEndpoints.putIfAbsent(it, slot) }
        }

        val freeSlots = (CsqttConstants.Profiles.MIN_INDEX..CsqttConstants.Profiles.MAX_INDEX)
            .filter { it !in occupancy }
            .toMutableList()
        val usedSlots = mutableSetOf<Int>()
        val seenEndpoints = mutableSetOf<String>()
        val writes = mutableListOf<SubscriptionProfileWrite>()
        var imported = 0
        var updated = 0
        var skipped = preview.skipped

        for (node in preview.nodes) {
            val endpoint = endpointKey(node.host, node.port)
            if (endpoint == null || !seenEndpoints.add(endpoint)) {
                skipped++
                continue
            }

            val uuid = node.subUuid.orEmpty()
            val matched = (if (uuid.isNotEmpty()) byUuid[uuid] else null)
                ?: byEndpoint[endpoint]
                ?: if (preview.isSingle) existingForUrl.firstOrNull { it !in usedSlots } else null

            if (matched != null) {
                if (matched in usedSlots) {
                    skipped++
                    continue
                }
                usedSlots += matched
                writes += node.toWrite(matched, url, preview.title)
                updated++
                if (uuid.isNotEmpty()) byUuid[uuid] = matched
                byEndpoint[endpoint] = matched
                globalEndpoints[endpoint] = matched
                continue
            }

            // Address already belongs to another profile (or another node of this
            // subscription) — do not duplicate it in a limited set of slots.
            if (globalEndpoints.containsKey(endpoint)) {
                skipped++
                continue
            }

            val slot = freeSlots.firstOrNull { it !in usedSlots }
            if (slot == null) {
                skipped++
                continue
            }
            usedSlots += slot
            writes += node.toWrite(slot, url, preview.title)
            imported++
            if (uuid.isNotEmpty()) byUuid[uuid] = slot
            byEndpoint[endpoint] = slot
            globalEndpoints[endpoint] = slot
        }

        store.applySubscriptionImport(writes)
        store.recordSubscriptionImport(
            url = url,
            title = preview.title,
            allowHttp = allowHttp,
        )
        return SubscriptionImportSummary(
            imported = imported,
            updated = updated,
            skipped = skipped,
            firstProfile = writes.firstOrNull()?.profile,
        )
    }

    private fun SubscriptionNode.toWrite(
        profile: Int,
        url: String,
        title: String,
    ): SubscriptionProfileWrite = SubscriptionProfileWrite(
        profile = profile,
        link = link,
        power = power,
        nodeName = name,
        title = title,
        url = url,
        subUuid = subUuid,
    )

    internal fun ProfileConnectionSnapshot.endpointKey(): String? {
        // a link-mode profile connects to its link's server (a subscription node is one now, M-170)
        if (csqttLinkMode) return parseCsqttLink(csqttLink)?.let { endpointKey(it.host, it.port) }
        val raw = peer.trim()
        if (raw.isEmpty()) return null
        if (raw.startsWith('[')) {
            val close = raw.indexOf(']')
            if (close <= 1) return null
            val host = raw.substring(1, close)
            val rest = raw.substring(close + 1)
            val port = if (rest.startsWith(':')) rest.substring(1).toIntOrNull() else null
            return endpointKey(host, port ?: serverPeerPort)
        }
        val hostPort = Regex("^(.*):(\\d{1,5})$").matchEntire(raw)
        return if (hostPort != null) {
            endpointKey(hostPort.groupValues[1], hostPort.groupValues[2].toIntOrNull() ?: serverPeerPort)
        } else {
            endpointKey(raw, serverPeerPort)
        }
    }

    private fun endpointKey(host: String, port: Int): String? {
        val normalized = host
            .trim()
            .removePrefix("[")
            .removeSuffix("]")
            .trimEnd('.')
            .lowercase(Locale.ROOT)
        if (normalized.isEmpty() || port !in 1..65535) return null
        return "$normalized|$port"
    }

    private fun candidateUrls(uri: URI): List<String> {
        val path = uri.path.orEmpty()
        val candidates = LinkedHashSet<String>()

        fun withPath(newPath: String): String? = runCatching {
            URI(uri.scheme, uri.userInfo, uri.host, uri.port, newPath, uri.query, uri.fragment).toString()
        }.getOrNull()

        when {
            path.endsWith("/sub") -> {
                withPath(path.dropLast(4) + "/json")?.let(candidates::add)
                withPath(path.dropLast(4) + "/sub.json")?.let(candidates::add)
                candidates += uri.toString()
            }
            path.endsWith("/json") || path.endsWith(".json") -> candidates += uri.toString()
            else -> {
                withPath("$path/json")?.let(candidates::add)
                withPath("$path/sub.json")?.let(candidates::add)
                withPath("$path/sub")?.let(candidates::add)
                withPath("$path.json")?.let(candidates::add)
                candidates += uri.toString()
            }
        }
        return candidates.toList()
    }

    private fun fetchBody(
        rawUrl: String,
        pin: SubscriptionCertPin?,
        redirectsLeft: Int = MAX_REDIRECTS,
    ): BodyResult {
        val connection = runCatching {
            val target = URL(rawUrl)
            (target.openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "csqtt-client")
                setRequestProperty("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
                // the subscription's own server, marked by the user: a self-signed certificate is held to its pin, and
                // then the pin vouches for the name too (no CA did); every other host is checked by the platform alone
                if (pin != null && this is javax.net.ssl.HttpsURLConnection && pin.covers(target)) {
                    sslSocketFactory = pin.socketFactory
                    hostnameVerifier = pin.hostnameVerifier
                }
            }
        }.getOrElse {
            return BodyResult.Failed("Ошибка сети: ${it.message ?: "нет соединения"}")
        }

        return try {
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) {
                    BodyResult.Failed("Сервер вернул $code")
                } else if (redirectsLeft <= 0) {
                    BodyResult.Failed("Слишком много перенаправлений")
                } else {
                    val next = runCatching { URL(URL(rawUrl), location).toString() }.getOrNull()
                    if (next == null) {
                        BodyResult.Failed("Некорректное перенаправление")
                    } else if (!isAllowedScheme(next)) {
                        BodyResult.Failed("Поддерживаются только HTTPS-ссылки")
                    } else {
                        fetchBody(next, pin, redirectsLeft - 1)
                    }
                }
            } else if (code !in 200..299) {
                BodyResult.Failed("Сервер вернул $code")
            } else {
                val length = connection.contentLengthLong
                if (length > MAX_BODY_BYTES) {
                    BodyResult.Failed("Ответ подписки слишком большой")
                } else {
                    val bytes = connection.inputStream.use(::readLimited)
                    if (bytes.isEmpty()) {
                        BodyResult.Failed("Пустой ответ подписки")
                    } else {
                        BodyResult.Body(String(bytes, charsetOf(connection)))
                    }
                }
            }
        } catch (e: javax.net.ssl.SSLException) {
            if (generateSequence<Throwable>(e) { it.cause }.any { it.message == SubscriptionCertPin.MISMATCH }) {
                BodyResult.Failed(PIN_MISMATCH_MESSAGE)
            } else {
                BodyResult.Failed("Ошибка TLS: ${e.message ?: "сертификат не принят"}", tls = true)
            }
        } catch (e: Exception) {
            BodyResult.Failed("Ошибка сети: ${e.message ?: "нет соединения"}")
        } finally {
            connection.disconnect()
        }
    }

    private fun isAllowedScheme(url: String): Boolean =
        runCatching { URI(url).scheme }.getOrNull()?.lowercase(Locale.ROOT) == "https"

    private fun charsetOf(connection: HttpURLConnection): Charset {
        val name = connection.contentType
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim('"', ' ')
            ?: return Charsets.UTF_8
        return runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }

    private fun readLimited(stream: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (output.size() + read > MAX_BODY_BYTES) {
                throw IllegalStateException("Ответ подписки слишком большой")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun parseBody(text: String): ParseResult {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) {
            val array = runCatching { JSONArray(trimmed) }.getOrNull()
            if (array != null) return parseNodeArray(array, "Подписка")
        }
        val json = runCatching { JSONObject(trimmed) }.getOrNull()
        return if (json != null) parseJson(json) else parseHtml(text)
    }

    private fun parseJson(json: JSONObject): ParseResult {
        if (!json.optBoolean("ok", false)) {
            val error = json.optString("error").trim().ifBlank { "Сервер отклонил подписку" }
            return ParseResult.Rejected(error)
        }
        val group = json.optJSONObject("group")
        val title = group?.optString("name")?.trim()?.ifBlank { "Подписка" } ?: "Подписка"
        val items = group?.optJSONArray("items")
            ?: group?.optJSONArray("clients")
            ?: json.optJSONArray("clients")
            ?: json.optJSONArray("items")
        if (items != null) return parseNodeArray(items, title)
        if (group != null) return ParseResult.Invalid("В подписке нет узлов")
        return parseSingle(json)
    }

    private fun parseNodeArray(items: JSONArray, title: String): ParseResult {
        val nodes = mutableListOf<SubscriptionNode>()
        var skipped = 0
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index)
            val node = item?.let { nodeFrom(it, "Узел ${index + 1}") }
            if (node == null) {
                skipped++
            } else {
                nodes += node
            }
        }
        if (nodes.isEmpty()) {
            return ParseResult.Invalid(
                if (skipped > 0) "Не удалось расшифровать узлы подписки" else "В подписке нет узлов",
            )
        }
        return ParseResult.Success(SubscriptionPreview(title, nodes, skipped, isSingle = false))
    }

    private fun parseSingle(json: JSONObject): ParseResult {
        val title = json.optString("name").trim().ifBlank { "Подписка" }
        val rawLink = json.optString("link").trim()
        if (rawLink.isEmpty()) return ParseResult.Invalid("В подписке нет ссылки")
        val node = nodeFrom(json, title) ?: return ParseResult.Invalid("Не удалось расшифровать конфигурацию")
        return ParseResult.Success(SubscriptionPreview(title, listOf(node), 0, isSingle = true))
    }

    private fun nodeFrom(obj: JSONObject, fallbackName: String): SubscriptionNode? {
        val rawLink = obj.optString("link").trim()
        if (rawLink.isEmpty()) return null
        val parsed = parseCsqttLink(rawLink) ?: return null
        if (parsed.password.isBlank()) return null
        return SubscriptionNode(
            link = rawLink,
            name = obj.optString("name").trim().ifBlank { fallbackName },
            host = parsed.host,
            port = parsed.port,
            password = parsed.password,
            power = readPower(obj) ?: parsed.power,
            hashes = parsed.hashes,
            subUuid = obj.optString("sub_uuid").trim().ifEmpty { null },
        )
    }

    private fun readPower(obj: JSONObject): Int? {
        val raw = obj.opt("power") ?: obj.opt("workers")
        val value = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
        return normalizeCsqttPower(value)
    }

    private val titlePattern = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val headingPattern = Regex("<h[12][^>]*>(.*?)</h[12]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val tagPattern = Regex("<[^>]+>")
    private val linkPattern = Regex("""csqtt://[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    private fun parseHtml(html: String): ParseResult {
        val rawLinks = linkPattern.findAll(html).map { it.value }.distinct().toList()
        if (rawLinks.isEmpty()) return ParseResult.Invalid("В ответе нет ссылки csqtt://")
        val title = (titlePattern.find(html) ?: headingPattern.find(html))
            ?.groupValues
            ?.get(1)
            ?.replace(tagPattern, "")
            ?.trim()
            ?.ifBlank { "Подписка" }
            ?: "Подписка"
        val nodes = mutableListOf<SubscriptionNode>()
        var skipped = 0
        for (rawLink in rawLinks) {
            val parsed = parseCsqttLink(rawLink)
            if (parsed == null || parsed.password.isBlank()) {
                skipped++
                continue
            }
            nodes += SubscriptionNode(
                link = rawLink,
                name = title,
                host = parsed.host,
                port = parsed.port,
                password = parsed.password,
                power = parsed.power,
                hashes = parsed.hashes,
                subUuid = null,
            )
        }
        if (nodes.isEmpty()) return ParseResult.Invalid("Не удалось расшифровать конфигурацию")
        return ParseResult.Success(
            SubscriptionPreview(
                title = title,
                nodes = nodes,
                skipped = skipped,
                isSingle = nodes.size == 1,
            ),
        )
    }
}
