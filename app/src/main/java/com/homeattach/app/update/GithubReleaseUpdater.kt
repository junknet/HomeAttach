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
import java.util.Locale
import org.json.JSONObject

data class AvailableAppUpdate(
    val tagName: String,
    val releaseUrl: String,
    val assetName: String,
    val assetUrl: String,
    val assetSizeBytes: Long,
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
    fun checkForUpdate(): AppUpdateCheckResult {
        val owner = BuildConfig.UPDATE_REPOSITORY_OWNER.trim()
        val repo = BuildConfig.UPDATE_REPOSITORY_NAME.trim()
        if (owner.isBlank() || repo.isBlank()) return AppUpdateCheckResult.NotConfigured
        require(GITHUB_REPOSITORY_PART.matches(owner)) { "Invalid GitHub owner: $owner" }
        require(GITHUB_REPOSITORY_PART.matches(repo)) { "Invalid GitHub repository: $repo" }

        val releaseJson = getJson(
            "https://api.github.com/repos/$owner/$repo/releases/latest",
        )
        val latestTag = releaseJson.getString("tag_name")
        val apkAsset = selectApkAsset(releaseJson)
            ?: throw IOException("Latest GitHub release has no APK asset")

        if (!isReleaseNewerThanInstalled(latestTag, BuildConfig.VERSION_NAME)) {
            return AppUpdateCheckResult.UpToDate(BuildConfig.VERSION_NAME, latestTag)
        }

        return AppUpdateCheckResult.Available(
            AvailableAppUpdate(
                tagName = latestTag,
                releaseUrl = releaseJson.optString("html_url"),
                assetName = apkAsset.getString("name"),
                assetUrl = apkAsset.getString("browser_download_url"),
                assetSizeBytes = apkAsset.optLong("size", -1L),
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
            partial.outputStream().use { output ->
                connection.inputStream.use { input -> input.copyTo(output) }
            }
            if (partial.length() <= 0L) {
                throw IOException("Downloaded APK is empty")
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
            if (responseCode !in 200..299) {
                throw IOException("GitHub release check failed: HTTP $responseCode")
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
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "HomeAttach/${BuildConfig.VERSION_NAME}")
        }
    }

    private fun selectApkAsset(releaseJson: JSONObject): JSONObject? {
        val assets = releaseJson.getJSONArray("assets")
        val apkAssets = buildList {
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                if (asset.getString("name").endsWith(".apk", ignoreCase = true)) {
                    add(asset)
                }
            }
        }
        return apkAssets.minWithOrNull(
            compareBy<JSONObject> { apkAssetPriority(it.getString("name")) }
                .thenBy { it.getString("name") },
        )
    }

    private fun apkAssetPriority(name: String): Int {
        val lower = name.lowercase(Locale.US)
        return when {
            "universal" in lower -> 0
            "release" in lower -> 1
            else -> 2
        }
    }

    private fun safeFilePart(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "latest" }
    }

    companion object {
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val NETWORK_TIMEOUT_MS = 15000
        private const val UPDATE_CACHE_DIR = "updates"
        private val GITHUB_REPOSITORY_PART = Regex("[A-Za-z0-9_.-]+")
    }
}

internal fun isReleaseNewerThanInstalled(releaseTag: String, installedVersion: String): Boolean {
    val release = releaseTag.trim().removePrefix("v").removePrefix("V")
    val installed = installedVersion.trim().removePrefix("v").removePrefix("V")
    val releaseParts = release.splitToSequence(Regex("[^0-9]+"))
        .filter(String::isNotBlank)
        .mapNotNull(String::toIntOrNull)
        .toList()
    val installedParts = installed.splitToSequence(Regex("[^0-9]+"))
        .filter(String::isNotBlank)
        .mapNotNull(String::toIntOrNull)
        .toList()
    val maxSize = maxOf(releaseParts.size, installedParts.size)
    for (index in 0 until maxSize) {
        val left = releaseParts.getOrNull(index) ?: 0
        val right = installedParts.getOrNull(index) ?: 0
        if (left != right) return left > right
    }
    return false
}
