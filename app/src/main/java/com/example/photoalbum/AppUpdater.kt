package com.example.photoalbum

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class GitHubReleaseInfo(
    val tagName: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val releaseNotes: String
)

object AppUpdater {

    private data class ParsedVersion(
        val parts: List<Int>,
        val buildNumber: Long
    )

    private fun parseVersionTag(raw: String, fallbackBuild: Long = 1L): ParsedVersion {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
        val splitByBuild = cleaned.split("-b", "-B", "+", limit = 2)
        val versionPart = splitByBuild[0]
        val buildPart = splitByBuild.getOrNull(1)?.filter { it.isDigit() }?.toLongOrNull() ?: fallbackBuild
        val parts = versionPart.split(".").map { segment ->
            segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        return ParsedVersion(parts = parts, buildNumber = buildPart)
    }

    /**
     * 統一版本顯示規則：
     * - build >= 2 時顯示主版號與組建號（例如 "1.1.5-b2"）
     * - build <= 1 時只顯示主版號（例如 "1.1.5"）
     */
    fun formatDisplayVersion(rawVersionOrTag: String, fallbackBuild: Long = 1L): String {
        val parsed = parseVersionTag(rawVersionOrTag, fallbackBuild = fallbackBuild)
        val baseVersion = parsed.parts.joinToString(".")
        return if (parsed.buildNumber >= 2L) {
            "$baseVersion-b${parsed.buildNumber}"
        } else {
            baseVersion
        }
    }

    fun isNewerVersion(
        remoteTag: String,
        currentVersion: String,
        currentBuildNumber: Long = 1L
    ): Boolean {
        val remote = parseVersionTag(remoteTag, fallbackBuild = 1L)
        val local = parseVersionTag(currentVersion, fallbackBuild = currentBuildNumber)

        val maxLen = maxOf(remote.parts.size, local.parts.size)
        for (i in 0 until maxLen) {
            val r = remote.parts.getOrElse(i) { 0 }
            val l = local.parts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }

        return remote.buildNumber > local.buildNumber
    }

    suspend fun fetchLatestGitHubRelease(): GitHubReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/Henryliu880922/PhotoAlbum/releases/latest")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 8000
                readTimeout = 8000
            }
            if (conn.responseCode != 200) return@withContext null

            val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonText)
            val tagName = json.optString("tag_name", "")
            val body = json.optString("body", "")
            val assets = json.optJSONArray("assets") ?: return@withContext null

            var apkUrl = ""
            var apkName = "PhotoAlbum-update.apk"
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                if (name.endsWith(".apk")) {
                    apkName = name
                    apkUrl = asset.optString("browser_download_url", "")
                    break
                }
            }
            if (tagName.isNotEmpty() && apkUrl.isNotEmpty()) {
                GitHubReleaseInfo(tagName, apkUrl, apkName, body)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun downloadAndInstallApk(
        context: Context,
        apkUrl: String,
        fileName: String,
        onProgress: (Int) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val apkFile = File(context.cacheDir, fileName)
            if (apkFile.exists()) apkFile.delete()

            val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 10000
                readTimeout = 20000
            }
            val totalBytes = conn.contentLength
            var downloadedBytes = 0L

            conn.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            val pct = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                            withContext(Dispatchers.Main) { onProgress(pct) }
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                val apkUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(installIntent)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onError(e.localizedMessage ?: "下載失敗")
            }
        }
    }
}
