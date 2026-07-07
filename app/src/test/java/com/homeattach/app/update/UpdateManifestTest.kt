package com.homeattach.app.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {
    // ---- versionCode comparison: the whole update decision ----

    @Test
    fun higherManifestVersionCodeIsNewer() {
        assertTrue(isVersionCodeNewer(2, 1))
    }

    @Test
    fun equalVersionCodeIsNotNewer() {
        assertFalse(isVersionCodeNewer(5, 5))
    }

    @Test
    fun lowerVersionCodeIsNotNewer_neverOffersDowngrade() {
        // Device on code 5, manifest reset to 1 (the v1.0.0 clean-slate case): must NOT offer it.
        assertFalse(isVersionCodeNewer(1, 5))
    }

    // ---- manifest parsing ----

    private fun manifestJson(
        versionCode: Any? = 3,
        versionName: String? = "1.0.2",
        apkUrl: String? = "https://github.com/o/r/releases/download/v1.0.2/app-release.apk",
        sha256: String? = "a".repeat(64),
        extra: Pair<String, Any>? = null,
    ): JSONObject = JSONObject().apply {
        versionCode?.let { put("versionCode", it) }
        versionName?.let { put("versionName", it) }
        apkUrl?.let { put("apkUrl", it) }
        sha256?.let { put("sha256", it) }
        put("sizeBytes", 15_000_000L)
        extra?.let { put(it.first, it.second) }
    }

    @Test
    fun parsesValidManifest() {
        val m = parseManifest(manifestJson())
        assertEquals(3, m.versionCode)
        assertEquals("1.0.2", m.versionName)
        assertEquals("https://github.com/o/r/releases/download/v1.0.2/app-release.apk", m.apkUrl)
        assertEquals("a".repeat(64), m.sha256)
        assertEquals(15_000_000L, m.sizeBytes)
    }

    @Test
    fun unknownExtraKeyIsIgnored_forwardCompatible() {
        val m = parseManifest(manifestJson(extra = "minSdkFuture" to 99))
        assertEquals(3, m.versionCode)
    }

    @Test
    fun sha256IsCaseInsensitiveAndNormalizedToLower() {
        val m = parseManifest(manifestJson(sha256 = "A".repeat(64)))
        assertEquals("a".repeat(64), m.sha256)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingVersionCode() {
        parseManifest(manifestJson(versionCode = null))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonHttpsApkUrl() {
        parseManifest(manifestJson(apkUrl = "http://github.com/o/r/app.apk"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedSha256() {
        parseManifest(manifestJson(sha256 = "deadbeef"))
    }
}
