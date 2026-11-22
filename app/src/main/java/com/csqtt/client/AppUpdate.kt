package com.csqtt.client

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

const val UPDATE_CHECK_NEVER = CsqttConstants.Update.CHECK_NEVER
const val DEFAULT_UPDATE_CHECK_INTERVAL_HOURS = CsqttConstants.Update.DEFAULT_CHECK_INTERVAL_HOURS
const val UPDATE_DIALOG_ACTION_POSTPONED = CsqttConstants.Update.ACTION_POSTPONED
const val UPDATE_DIALOG_ACTION_UPDATE = CsqttConstants.Update.ACTION_UPDATE

private const val UPDATE_LOG_TAG = CsqttConstants.General.APP_NAME
private const val GITHUB_RELEASES_URL = CsqttConstants.Update.GITHUB_RELEASES_URL
private const val GITHUB_API_RATE_LIMIT_FALLBACK_MS = CsqttConstants.Timeouts.UPDATE_RATE_LIMIT_COOLDOWN_MS

@Volatile
private var githubApiCooldownUntilMs = 0L

/** M-91: how long GitHub's API is still refusing this address (403/429), 0 when it is not. */
fun updateCooldownRemainingMs(nowMs: Long = System.currentTimeMillis()): Long =
    (githubApiCooldownUntilMs - nowMs).coerceAtLeast(0L)

fun updateIntervalHoursToMillis(hours: Int): Long? = when {
    hours <= 0 -> null
    else -> hours * 60L * 60L * 1000L
}

/**
 * How long to wait before the next automatic update check: 0 when one is due. Counted from the last check, so the UI
 * (rebuilt on every return to the foreground) does not check each time it starts. A last check in the future (the clock
 * was set back) counts as due, so a wrong clock cannot stop the checks for days.
 */
fun updateCheckDelayMs(nowMs: Long, lastCheckMs: Long, intervalMs: Long): Long = when {
    lastCheckMs <= 0L || lastCheckMs > nowMs -> 0L
    else -> (lastCheckMs + intervalMs - nowMs).coerceAtLeast(0L)
}

data class AppReleaseInfo(
    val versionTag: String,
    val releaseUrl: String,
)

/** One release of our feed, as GitHub's releases list gives it (M-91). */
internal data class FeedRelease(
    val tag: String,
    val url: String,
    val prerelease: Boolean,
    val draft: Boolean,
    val assets: List<String>,
)

/**
 * A version as our tags and builds write it: `2.6.0`, `v2.6.1-beta2`, `2.7.0-rc1`. A prerelease (alpha < beta < rc)
 * sorts below its release; any other suffix (the fork test's `-fork-test.3`) is not read.
 */
internal data class AppVersion(val numbers: List<Int>, val stage: Int, val stageNumber: Int) : Comparable<AppVersion> {
    val isPrerelease: Boolean get() = stage < RELEASE

    override fun compareTo(other: AppVersion): Int {
        for (index in 0 until maxOf(numbers.size, other.numbers.size)) {
            val difference = numbers.getOrElse(index) { 0 }.compareTo(other.numbers.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        if (stage != other.stage) return stage.compareTo(other.stage)
        return stageNumber.compareTo(other.stageNumber)
    }

    companion object {
        private const val RELEASE = 3
        private val STAGES = listOf("alpha", "beta", "rc")
        private val PATTERN = Regex("^v?(\\d+(?:\\.\\d+)*)(?:-(alpha|beta|rc)[.-]?(\\d+)?)?", RegexOption.IGNORE_CASE)

        fun parse(text: String): AppVersion? {
            val match = PATTERN.find(text.trim()) ?: return null
            val numbers = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
            val stage = match.groupValues[2].lowercase().takeIf { it.isNotEmpty() }?.let(STAGES::indexOf) ?: RELEASE
            return AppVersion(numbers, stage, match.groupValues[3].toIntOrNull() ?: 0)
        }
    }
}

fun isNewerVersion(local: String, remote: String): Boolean {
    val remoteVersion = AppVersion.parse(remote) ?: return false
    val localVersion = AppVersion.parse(local) ?: return true
    return remoteVersion > localVersion
}

/** GitHub's releases list, newest first, or null when it could not be read. */
internal fun parseReleaseFeed(json: String): List<FeedRelease>? {
    val array = runCatching { JSONArray(json) }.getOrNull() ?: return null
    return (0 until array.length()).mapNotNull { index ->
        val release = array.optJSONObject(index) ?: return@mapNotNull null
        val tag = release.optString("tag_name").trim()
        val url = release.optString("html_url").trim()
        if (tag.isEmpty() || url.isEmpty()) return@mapNotNull null
        val assets = release.optJSONArray("assets")
        FeedRelease(
            tag = tag,
            url = url,
            prerelease = release.optBoolean("prerelease"),
            draft = release.optBoolean("draft"),
            assets = (0 until (assets?.length() ?: 0)).mapNotNull { assets?.optJSONObject(it)?.optString("name") },
        )
    }
}

/**
 * M-91: the newest release this build could install, newer or not (the caller compares), or null. Only a published
 * release carrying this app's own APK (an asset named `<application ID>-…apk`: our releases name them so at upload)
 * counts, so a release with server assets only, another app's APK and the fork test's build (another ID) never do; a
 * prerelease counts only for a prerelease build.
 */
internal fun pickLatest(feed: List<FeedRelease>, localVersion: String, applicationId: String): FeedRelease? {
    val local = AppVersion.parse(localVersion)
    return feed
        .filter { !it.draft && (!it.prerelease || local?.isPrerelease == true) }
        .filter { release -> release.assets.any { it.startsWith("$applicationId-") && it.endsWith(".apk") } }
        .mapNotNull { release -> AppVersion.parse(release.tag)?.let { release to it } }
        .maxByOrNull { (_, version) -> version }
        ?.first
}

/**
 * One request a check (M-91; before, up to four): our releases list → the newest release this build could install,
 * null when there is none, a failure when the list could not be read.
 */
suspend fun fetchLatestRelease(localVersion: String): Result<AppReleaseInfo?> = withContext(Dispatchers.IO) {
    val feed = fetchGitHubApi(GITHUB_RELEASES_URL)?.let(::parseReleaseFeed)
        ?: return@withContext Result.failure(IllegalStateException("the releases list could not be read"))
    Result.success(pickLatest(feed, localVersion, BuildConfig.APPLICATION_ID)?.let { AppReleaseInfo(it.tag, it.url) })
}

private fun fetchGitHubApi(url: String): String? {
    val now = System.currentTimeMillis()
    if (now < githubApiCooldownUntilMs) return null
    var conn: HttpURLConnection? = null
    return try {
        conn = URL(url).openConnection() as HttpURLConnection
        conn.useCaches = false
        conn.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
        conn.setRequestProperty("Pragma", "no-cache")
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("User-Agent", "CSQTTAndroid/${BuildConfig.VERSION_NAME}")
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000

        val responseCode = conn.responseCode
        val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (responseCode in 200..299) {
            githubApiCooldownUntilMs = 0L
            response
        } else {
            noteGitHubApiCooldown(conn, responseCode, response)
            Log.w(UPDATE_LOG_TAG, "[WARN] Update check: GitHub API returned $responseCode ${response.take(300)}")
            null
        }
    } catch (e: Exception) {
        Log.w(UPDATE_LOG_TAG, "[WARN] Update check: GitHub API request failed", e)
        null
    } finally {
        conn?.disconnect()
    }
}

private fun noteGitHubApiCooldown(conn: HttpURLConnection, responseCode: Int, response: String) {
    if (responseCode != HttpURLConnection.HTTP_FORBIDDEN && responseCode != 429) return
    val now = System.currentTimeMillis()
    val retryAfterUntil = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.let { now + it * 1000L }
    val rateLimitResetUntil = conn.getHeaderField("X-RateLimit-Reset")?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.let { it * 1000L }
    val fallbackUntil = now + if (response.contains("rate limit", ignoreCase = true)) GITHUB_API_RATE_LIMIT_FALLBACK_MS else 5L * 60L * 1000L
    val cooldownUntil = listOfNotNull(retryAfterUntil, rateLimitResetUntil).filter { it > now }.minOrNull() ?: fallbackUntil
    if (cooldownUntil > githubApiCooldownUntilMs) {
        githubApiCooldownUntilMs = cooldownUntil
        Log.w(
            UPDATE_LOG_TAG,
            "[WARN] Update check: GitHub API cooldown ${(cooldownUntil - now) / 1000}s after HTTP $responseCode"
        )
    }
}
