package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M-91 (B5g): our release feed — only this app's APK, prereleases only for prerelease builds, versions in order. */
class UpdateFeedFilterTest {
    private val app = "net.sanityprotocol.csqtt"

    private fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false, vararg assets: String) =
        FeedRelease(tag, "https://github.com/SanityProtocol/csqtt/releases/tag/$tag", prerelease, draft, assets.toList())

    private fun apk(version: String, id: String = app) = "$id-$version-arm64-v8a.apk"

    @Test
    fun `versions order by number, and a prerelease sorts below its release`() {
        val ordered = listOf("2.5.3", "2.6.0-alpha1", "2.6.0-beta1", "2.6.0-beta2", "2.6.0-rc1", "2.6.0", "v2.6.1", "2.10.0")
        val parsed = ordered.map { requireNotNull(AppVersion.parse(it)) { it } }
        assertEquals(parsed, parsed.sorted())
        assertTrue(AppVersion.parse("2.6.0-beta.3")!!.isPrerelease)
        assertTrue(AppVersion.parse("v2.6.0-RC2")!!.isPrerelease)
        // the fork test's suffix is not a stage: its build compares as 2.6.0
        assertFalse(AppVersion.parse("2.6.0-fork-test.3")!!.isPrerelease)
        assertNull(AppVersion.parse("latest"))
        assertTrue(isNewerVersion("2.6.0-beta2", "2.6.0"))
        assertFalse(isNewerVersion("2.6.0", "2.6.0-rc1"))
        assertTrue(isNewerVersion("1.9.9", "2.0.5"))
        assertFalse(isNewerVersion("2.0.5", "invalid"))
    }

    @Test
    fun `a stable build sees stable releases only, a prerelease build both`() {
        val feed = listOf(
            release("v2.7.0-beta1", prerelease = true, assets = arrayOf(apk("2.7.0-beta1"))),
            release("v2.6.1", assets = arrayOf(apk("2.6.1"))),
        )
        assertEquals("v2.6.1", pickLatest(feed, "2.6.0", app)?.tag)
        assertEquals("v2.7.0-beta1", pickLatest(feed, "2.6.1-beta3", app)?.tag)
        // a prerelease build whose release is out moves on to the release
        assertEquals("v2.6.1", pickLatest(listOf(release("v2.6.1", assets = arrayOf(apk("2.6.1")))), "2.6.1-rc1", app)?.tag)
    }

    @Test
    fun `only a published release with this app's own APK counts`() {
        val feed = listOf(
            release("v2.9.0", draft = true, assets = arrayOf(apk("2.9.0"))),
            release("v2.8.0", assets = arrayOf("csqtt-linux-x86_64", "csqtt-linux-aarch64")),
            release("v2.7.5", assets = arrayOf(apk("2.7.5", id = "com.csqtt.client"))),
            release("v2.7.4", assets = arrayOf(apk("2.7.4", id = "$app.forktest"))),
            release("v2.7.3", assets = arrayOf("$app-2.7.3-arm64-v8a.apk.sha256")),
            release("v2.6.2", assets = arrayOf("csqtt-linux-x86_64", apk("2.6.2"))),
        )
        assertEquals("v2.6.2", pickLatest(feed, "2.6.0", app)?.tag)
        // the fork test's build (another ID) finds only its own APKs
        assertEquals("v2.7.4", pickLatest(feed, "2.6.0-fork-test.3", "$app.forktest")?.tag)
        assertNull(pickLatest(feed.take(5), "2.6.0", app))
    }

    @Test
    fun `the newest by version wins, whatever the list's order`() {
        val feed = listOf(
            release("v2.6.1", assets = arrayOf(apk("2.6.1"))),
            release("v2.6.10", assets = arrayOf(apk("2.6.10"))),
            release("v2.6.2", assets = arrayOf(apk("2.6.2"))),
        )
        assertEquals("v2.6.10", pickLatest(feed, "2.6.0", app)?.tag)
        // the caller compares: the latest may be this build itself
        assertEquals("v2.6.10", pickLatest(feed, "2.6.10", app)?.tag)
        assertFalse(isNewerVersion("v2.6.10", "v2.6.10"))
    }

    @Test
    fun `GitHub's list is read with its assets, a bad entry left out and a bad list refused`() {
        val json = """[
            {"tag_name": "v2.6.1", "html_url": "https://github.com/SanityProtocol/csqtt/releases/tag/v2.6.1",
             "prerelease": false, "draft": false,
             "assets": [{"name": "net.sanityprotocol.csqtt-2.6.1-arm64-v8a.apk"}, {"name": "csqtt-linux-x86_64"}]},
            {"tag_name": "", "html_url": "x"},
            {"tag_name": "v2.7.0-beta1", "html_url": "https://example/b", "prerelease": true}
        ]"""
        val feed = parseReleaseFeed(json)!!
        assertEquals(listOf("v2.6.1", "v2.7.0-beta1"), feed.map { it.tag })
        assertEquals(listOf("net.sanityprotocol.csqtt-2.6.1-arm64-v8a.apk", "csqtt-linux-x86_64"), feed[0].assets)
        assertTrue(feed[1].prerelease)
        assertTrue(feed[1].assets.isEmpty())
        assertNull(parseReleaseFeed("""{"message": "API rate limit exceeded"}"""))
        assertNull(parseReleaseFeed("not json"))
    }

    @Test
    fun `«Позже» holds that version back for a day, not a newer one`() {
        val day = 24L * 60L * 60L * 1000L
        val at = 1_800_000_000_000L
        assertTrue(AppUpdates.isPostponed("v2.6.1", "v2.6.1", at + day, at + day - 1))
        assertFalse(AppUpdates.isPostponed("v2.6.1", "v2.6.1", at + day, at + day))
        assertFalse(AppUpdates.isPostponed("v2.6.2", "v2.6.1", at + day, at + 1))
        assertFalse(AppUpdates.isPostponed("v2.6.1", "", 0L, at))
    }
}
