package com.example.photoalbum

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
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
    val apkFileName: String
)

object AppUpdater {
    private const val REPO_OWNER = "Henryliu880922"
    private const val REPO_NAME = "PhotoAlbum"
    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

    /**
     * 解析版本號與組建號：
     * 支援 "v1.1.5"、"1.1.5"、"v1.1.5-b2" 等格式。
     * 若未帶 -bN 後綴，則回退使用 fallbackBuild（預設為 1L）。
     */
    private fun parseVersionAndBuild(
        rawVersionOrTag: String,
        fallbackBuild: Long = 1L
    ): Pair<List<Int>, Long> {
        val cleaned = rawVersionOrTag.trim().removePrefix("v").removePrefix("V")
        val parts = cleaned.split("-b", "-B", limit = 2)
        val versionNumbers = parts[0]
            .split(".")
            .map { segment -> segment.filter { it.isDigit() }.toIntOrNull() ?: 0 }

        val parsedBuild = if (parts.size > 1) {
            parts[1].filter { it.isDigit() }.toLongOrNull() ?: fallbackBuild
        } else {
            fallbackBuild
        }
        return versionNumbers to parsedBuild
    }

    /**
     * 統一格式化顯示版號：
     * build >= 2 時回傳 "1.1.5-b2"；build <= 1 時僅回傳主版號 "1.1.5"。
     */
    fun formatDisplayVersion(rawVersionOrTag: String, fallbackBuild: Long = 1L): String {
        val (numbers, build) = parseVersionAndBuild(rawVersionOrTag, fallbackBuild)
        val baseVersion = numbers.joinToString(".")
        return if (build >= 2L) "$baseVersion-b$build" else baseVersion
    }

    /**
     * 判斷遠端版本是否比目前安裝版本更新：
     * 先逐位比較主版號（Major.Minor.Patch），主版號相同時再比較組建號（Build Number）。
     */
    fun isNewerVersion(
        remoteTag: String,
        currentVersionName: String,
        currentBuildNumber: Long
    ): Boolean {
        val (remoteNumbers, remoteBuild) = parseVersionAndBuild(remoteTag, fallbackBuild = 1L)
        val (localNumbers, localBuild) = parseVersionAndBuild(currentVersionName, fallbackBuild = currentBuildNumber)

        val maxLen = maxOf(remoteNumbers.size, localNumbers.size)
        for (i in 0 until maxLen) {
            val r = remoteNumbers.getOrElse(i) { 0 }
            val l = localNumbers.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return remoteBuild > localBuild
    }

    /**
     * 從 GitHub Releases API 取得最新正式發布版本與 APK 下載連結
     */
    suspend fun fetchLatestGitHubRelease(): GitHubReleaseInfo? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(LATEST_RELEASE_API)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "PhotoAlbum-Android-Updater")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext null
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "").trim()
            if (tagName.isEmpty()) return@withContext null

            val assets = json.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                val downloadUrl = asset.optString("browser_download_url", "")
                if (name.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotEmpty()) {
                    return@withContext GitHubReleaseInfo(
                        tagName = tagName,
                        apkDownloadUrl = downloadUrl,
                        apkFileName = name
                    )
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 下載 APK 至外部專屬下載目錄（供系統安裝器讀取正確圖示與應用名稱）並啟動安裝
     */
    @SuppressLint("NewApi")
    suspend fun downloadAndInstallApk(
        context: Context,
        apkUrl: String,
        fileName: String,
        onProgress: (Int) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val downloadDir =
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }
            // 清理舊的更新暫存檔，節省空間
            downloadDir.listFiles()?.forEach { existing ->
                if (existing.isFile && existing.name.endsWith(".apk", ignoreCase = true)) {
                    existing.delete()
                }
            }

            val apkFile = File(downloadDir, fileName)
            val url = URL(apkUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "PhotoAlbum-Android-Updater")
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                withContext(Dispatchers.Main) {
                    onError("HTTP ${connection.responseCode}")
                }
                return@withContext
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L
            var lastReportedPct = -1

            connection.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0L) {
                            val pct = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            if (pct != lastReportedPct) {
                                lastReportedPct = pct
                                withContext(Dispatchers.Main) {
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                    output.flush()
                }
            }

            withContext(Dispatchers.Main) {
                launchApkInstaller(context, apkFile)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onError(e.localizedMessage ?: "Download failed")
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun launchApkInstaller(context: Context, apkFile: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri()
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(permissionIntent)
            return
        }

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
}
