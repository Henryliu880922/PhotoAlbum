package com.example.photoalbum

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
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
    private const val INSTALL_ACTION = "com.example.photoalbum.INSTALL_COMPLETE"

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

    fun formatDisplayVersion(rawVersionOrTag: String, fallbackBuild: Long = 1L): String {
        val (numbers, build) = parseVersionAndBuild(rawVersionOrTag, fallbackBuild)
        val baseVersion = numbers.joinToString(".")
        return if (build >= 2L) "$baseVersion-b$build" else baseVersion
    }

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

    suspend fun downloadAndInstallApk(
        context: Context,
        apkUrl: String,
        fileName: String,
        onProgress: (Int) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val apkFile = File(context.cacheDir, fileName)
            if (apkFile.exists()) apkFile.delete()

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

            installViaPackageInstaller(context, apkFile)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onError(e.localizedMessage ?: "Download failed")
            }
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun installViaPackageInstaller(context: Context, apkFile: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            withContext(Dispatchers.Main) {
                val permissionIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${context.packageName}".toUri()
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(permissionIntent)
            }
            return
        }

        val appContext = context.applicationContext
        val packageInstaller = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(appContext.packageName)
            setAppLabel(appContext.getString(R.string.app_name))
            runCatching {
                val drawable = appContext.packageManager.getApplicationIcon(appContext.packageName)
                setAppIcon(drawable.toBitmap(width = 192, height = 192))
            }
        }

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        session.openWrite("base.apk", 0, apkFile.length()).use { out ->
            FileInputStream(apkFile).use { input ->
                input.copyTo(out)
            }
            session.fsync(out)
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE
                )
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (confirmIntent != null) {
                        ctx.startActivity(confirmIntent)
                    }
                } else {
                    runCatching { appContext.unregisterReceiver(this) }
                    apkFile.delete()
                }
            }
        }

        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(INSTALL_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val callbackIntent = Intent(INSTALL_ACTION).apply {
            setPackage(appContext.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val statusReceiver = PendingIntent.getBroadcast(
            appContext,
            sessionId,
            callbackIntent,
            flags
        )

        session.commit(statusReceiver.intentSender)
        session.close()
    }
}
