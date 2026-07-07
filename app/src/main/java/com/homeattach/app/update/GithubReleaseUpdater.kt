package com.homeattach.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.homeattach.app.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONObject

data class AvailableAppUpdate(
    val tagName: String,
    val assetName: String,
    val assetUrl: String,
    val assetSizeBytes: Long,
    val sha256: String,
)

data class DownloadedAppUpdate(
    val update: AvailableAppUpdate,
    val apkFile: File,
)

sealed interface AppUpdateCheckResult {
    data object NotConfigured : AppUpdateCheckResult
    data class UpToDate(val currentVersion: String, val latestTag: String) : AppUpdateCheckResult
    data class Available(val update: AvailableAppUpdate) : AppUpdateCheckResult
}

enum class InstallLaunchResult {
    InstallerOpened,
    PermissionSettingsOpened,
}

class GithubReleaseUpdater(private val context: Context) {
    /**
     * Reads a static version manifest (update.json) served from a CDN direct-download URL rather
     * than the GitHub REST API. This deliberately avoids api.github.com: the anonymous REST endpoint
     * has a 60-req/hour/IP limit and `/releases/latest` hides draft/prerelease, both of which
     * silently broke the update channel. The manifest URL 302-redirects through GitHub's objects CDN
     * (no rate limit), and the update decision is a monotonic versionCode integer compare.
     */
    fun checkForUpdate(): AppUpdateCheckResult {
        val manifestUrl = BuildConfig.UPDATE_MANIFEST_URL.trim()
        if (manifestUrl.isBlank()) return AppUpdateCheckResult.NotConfigured

        val manifest = parseManifest(getJson(manifestUrl))
        if (!isVersionCodeNewer(manifest.versionCode, BuildConfig.VERSION_CODE)) {
            return AppUpdateCheckResult.UpToDate(BuildConfig.VERSION_NAME, manifest.versionName)
        }

        return AppUpdateCheckResult.Available(
            AvailableAppUpdate(
                tagName = manifest.versionName,
                assetName = manifest.apkUrl.substringAfterLast('/').substringBefore('?')
                    .ifBlank { "HomeAttach.apk" },
                assetUrl = manifest.apkUrl,
                assetSizeBytes = manifest.sizeBytes,
                sha256 = manifest.sha256,
            ),
        )
    }

    fun downloadApk(update: AvailableAppUpdate): DownloadedAppUpdate {
        val updatesDir = File(context.cacheDir, UPDATE_CACHE_DIR).apply {
            if (!isDirectory && !mkdirs()) {
                throw IOException("Could not create update cache: $absolutePath")
            }
        }
        updatesDir.listFiles()
            ?.filter { it.extension.equals("apk", ignoreCase = true) }
            ?.forEach { it.delete() }

        val target = File(updatesDir, "HomeAttach-${safeFilePart(update.tagName)}.apk")
        val partial = File(updatesDir, "${target.name}.partial")

        val connection = openConnection(update.assetUrl)
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("APK download failed: HTTP ${connection.responseCode}")
            }
            // Hash while streaming (single read) so a corrupt or tampered APK is rejected before it
            // is ever renamed into place or handed to the system installer.
            val digest = MessageDigest.getInstance("SHA-256")
            partial.outputStream().use { output ->
                DigestInputStream(connection.inputStream, digest).use { input -> input.copyTo(output) }
            }
            if (partial.length() <= 0L) {
                throw IOException("Downloaded APK is empty")
            }
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actualSha256.equals(update.sha256, ignoreCase = true)) {
                partial.delete()
                throw IOException("APK checksum mismatch: expected ${update.sha256}, got $actualSha256")
            }
            if (target.exists() && !target.delete()) {
                throw IOException("Could not replace cached APK: ${target.absolutePath}")
            }
            if (!partial.renameTo(target)) {
                throw IOException("Could not finalize cached APK: ${target.absolutePath}")
            }
        } finally {
            connection.disconnect()
            if (partial.exists()) partial.delete()
        }

        return DownloadedAppUpdate(update, target)
    }

    fun launchInstaller(downloaded: DownloadedAppUpdate): InstallLaunchResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return InstallLaunchResult.PermissionSettingsOpened
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.update_file_provider",
            downloaded.apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
        return InstallLaunchResult.InstallerOpened
    }

    private fun getJson(url: String): JSONObject {
        val connection = openConnection(url)
        try {
            val responseCode = connection.responseCode
            if (responseCode == 404) {
                throw IOException("No update manifest published yet (HTTP 404)")
            }
            if (responseCode !in 200..299) {
                throw IOException("Update check failed: HTTP $responseCode")
            }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = NETWORK_TIMEOUT_MS
            readTimeout = NETWORK_TIMEOUT_MS
            // The manifest URL 302-chains through GitHub's objects CDN; all hops are https->https so
            // HttpURLConnection follows them (it only refuses cross-protocol redirects).
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "HomeAttach/${BuildConfig.VERSION_NAME}")
        }
    }

    private fun safeFilePart(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "latest" }
    }

    companion object {
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val NETWORK_TIMEOUT_MS = 15000
        private const val UPDATE_CACHE_DIR = "updates"
    }
}

/** Parsed shape of the update.json version manifest. */
internal data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
)

/**
 * The whole update decision: a monotonic versionCode compare. versionCode only ever increases
 * (Android enforces it at install time too), so this can never mis-order releases the way the old
 * versionName string parsing could.
 */
internal fun isVersionCodeNewer(manifestVersionCode: Int, installedVersionCode: Int): Boolean =
    manifestVersionCode > installedVersionCode

/**
 * Strict manifest parse. Unknown keys are ignored (opt* accessors) so future fields are
 * forward-compatible; the fields we depend on are hard-validated so a malformed manifest fails the
 * check loudly instead of silently producing a bad update.
 */
internal fun parseManifest(json: JSONObject): UpdateManifest {
    val versionCode = json.optInt("versionCode", -1)
    require(versionCode > 0) { "Manifest missing valid versionCode" }
    val apkUrl = json.optString("apkUrl")
    require(apkUrl.startsWith("https://")) { "Manifest apkUrl must be https" }
    val sha256 = json.optString("sha256").lowercase(Locale.US)
    require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Manifest sha256 invalid" }
    return UpdateManifest(
        versionCode = versionCode,
        versionName = json.optString("versionName", versionCode.toString()),
        apkUrl = apkUrl,
        sha256 = sha256,
        sizeBytes = json.optLong("sizeBytes", -1L),
        notes = json.optString("notes"),
    )
}
