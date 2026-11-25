package com.example.photoalbum

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import coil.compose.AsyncImage
import com.example.photoalbum.ui.theme.PhotoAlbumTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration.Companion.seconds
import android.content.pm.ActivityInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.annotation.SuppressLint

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoAlbumApp()
        }
    }
}

private data class GitHubReleaseInfo(
    val tagName: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val releaseNotes: String
)

// 讀取目前 App 的版本名稱（純數字版號，例如 "1.0.0"）
private fun getCurrentVersionName(context: Context): String {
    return try {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        packageInfo.versionName ?: "1.0.0"
    } catch (_: Exception) {
        "1.0.0"
    }
}

// 讀取目前 App 的完整版本顯示字串
private fun getAppVersionInfo(context: Context): String {
    return try {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        val versionName = packageInfo.versionName ?: "1.0.0"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        "v$versionName (Build $versionCode)"
    } catch (_: Exception) {
        "v1.0.0"
    }
}

// 比較版本號（例如 "1.0.1" > "1.0.0" 回傳 true）
private fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
    val remoteParts = remoteTag.removePrefix("v").removePrefix("V").split(".").mapNotNull { it.toIntOrNull() }
    val localParts = currentVersion.removePrefix("v").removePrefix("V").split(".").mapNotNull { it.toIntOrNull() }
    val maxLen = maxOf(remoteParts.size, localParts.size)
    for (i in 0 until maxLen) {
        val r = remoteParts.getOrElse(i) { 0 }
        val l = localParts.getOrElse(i) { 0 }
        if (r > l) return true
        if (r < l) return false
    }
    return false
}

// 查詢 GitHub 最新 Release 資訊
private suspend fun fetchLatestGitHubRelease(): GitHubReleaseInfo? = withContext(Dispatchers.IO) {
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

// 下載新版 APK 並呼叫系統安裝程式
private suspend fun downloadAndInstallApk(
    context: Context,
    apkUrl: String,
    fileName: String,
    onProgress: (Int) -> Unit,
    onError: (String) -> Unit
) = withContext(Dispatchers.IO) {
    try {
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
        val apkFile = File(downloadDir, fileName)
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

// 即時監聽電池電量與充電狀態
@Composable
private fun rememberBatteryStatus(): Pair<Int, Boolean> {
    val context = LocalContext.current
    var batteryLevel by remember { mutableIntStateOf(100) }
    var isCharging by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent ?: return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    batteryLevel = (level * 100) / scale
                }
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val stickyIntent = context.registerReceiver(receiver, filter)
        if (stickyIntent != null) {
            val level = stickyIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = stickyIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                batteryLevel = (level * 100) / scale
            }
            val status = stickyIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
        }
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
    return batteryLevel to isCharging
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoAlbumApp() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("photo_album_prefs", Context.MODE_PRIVATE) }

    // --- 讀取已儲存的設定值（重開 App 不會重置） ---
    var gridColumns by remember { mutableIntStateOf(prefs.getInt("grid_columns", 3)) }
    var sortNewestFirst by remember { mutableStateOf(prefs.getBoolean("sort_newest_first", true)) }
    var selectedFolder by remember { mutableStateOf(prefs.getString("selected_folder", null)) }
    var slideshowSeconds by remember { mutableIntStateOf(prefs.getInt("slideshow_seconds", 5)) }
    var keepScreenOn by remember { mutableStateOf(prefs.getBoolean("keep_screen_on", true)) }
    var themeMode by remember { mutableIntStateOf(prefs.getInt("theme_mode", 0)) } // 0:系統, 1:淺色, 2:深色

    // 寫入設定的輔助函式
    fun saveInt(key: String, value: Int) = prefs.edit { putInt(key, value) }
    fun saveBoolean(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }
    fun saveString(key: String, value: String?) = prefs.edit { putString(key, value) }

    // 依據設定決定深色或淺色主題
    val useDarkTheme = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    PhotoAlbumTheme(darkTheme = useDarkTheme) {
        val lifecycleOwner = LocalLifecycleOwner.current
        var photoUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
        var folderList by remember { mutableStateOf<List<String>>(emptyList()) }
        var showSettings by remember { mutableStateOf(false) }

        // 改用 rememberSaveable：螢幕左橫/右橫旋轉時保留目前播放進度，不會跳出輪播
        var viewerIndex by rememberSaveable { mutableStateOf<Int?>(null) }
        var isSlideshowRunning by rememberSaveable { mutableStateOf(false) }
        var showViewerOverlay by rememberSaveable { mutableStateOf(true) }

        // GitHub 更新檢查狀態
        var isCheckingUpdate by remember { mutableStateOf(false) }
        var updateStatusMessage by remember { mutableStateOf<String?>(null) }
        var availableRelease by remember { mutableStateOf<GitHubReleaseInfo?>(null) }
        var isDownloadingApk by remember { mutableStateOf(false) }
        var downloadProgress by remember { mutableIntStateOf(0) }

        // 即時電量狀態
        val (batteryLevel, isCharging) = rememberBatteryStatus()

        // 控制全螢幕檢視或輪播時保持螢幕恆亮，並允許上下左右四向隨感應器自動旋轉
        @SuppressLint("SourceLockedOrientationActivity")
        DisposableEffect(viewerIndex, keepScreenOn) {
            val activity = context as? Activity
            val window = activity?.window
            if (viewerIndex != null) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                if (keepScreenOn) {
                    window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            } else {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }


        // 自動幻燈片輪播計時器（當手動翻頁改變 viewerIndex 時會自動重新計算秒數）
        LaunchedEffect(isSlideshowRunning, viewerIndex, slideshowSeconds, photoUris.size) {
            if (isSlideshowRunning && viewerIndex != null && photoUris.isNotEmpty()) {
                delay(slideshowSeconds.seconds)
                viewerIndex = ((viewerIndex ?: 0) + 1) % photoUris.size
            }
        }

        val permissions = remember {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES
                )
                else -> arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
            }
        }

        var hasPermission by remember {
            mutableStateOf(
                permissions.any {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
            )
        }

        // 統一重新讀取資料夾與照片的輔助函式
        fun refreshMediaLibrary() {
            if (!hasPermission) return
            val updatedFolders = loadImageFolders(context)
            folderList = updatedFolders
            if (selectedFolder != null && selectedFolder !in updatedFolders) {
                selectedFolder = null
                saveString("selected_folder", null)
            }
            val updatedPhotos = loadDeviceImages(context, sortNewestFirst, selectedFolder)
            photoUris = updatedPhotos
            // 若照片被刪除導致目前索引超出範圍，自動修正避免閃退
            val currentIdx = viewerIndex
            if (currentIdx != null) {
                if (updatedPhotos.isEmpty()) {
                    isSlideshowRunning = false
                    viewerIndex = null
                } else if (currentIdx >= updatedPhotos.size) {
                    viewerIndex = updatedPhotos.lastIndex
                }
            }
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            hasPermission = result.values.any { it }
            if (hasPermission) {
                refreshMediaLibrary()
            }
        }

        LaunchedEffect(hasPermission, sortNewestFirst, selectedFolder) {
            if (hasPermission) {
                refreshMediaLibrary()
            } else {
                permissionLauncher.launch(permissions)
            }
        }

        // 即時監聽系統相簿（MediaStore）變動，以及從背景切回前景時自動刷新照片
        DisposableEffect(hasPermission, sortNewestFirst, selectedFolder, lifecycleOwner) {
            val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    refreshMediaLibrary()
                }
            }
            val lifecycleObserver = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    refreshMediaLibrary()
                }
            }

            if (hasPermission) {
                context.contentResolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    contentObserver
                )
            }
            lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

            onDispose {
                context.contentResolver.unregisterContentObserver(contentObserver)
                lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            }
        }

        BackHandler(enabled = viewerIndex != null) {
            isSlideshowRunning = false
            viewerIndex = null
        }


        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            val titlePrefix = selectedFolder?.substringAfterLast("/") ?: "全部照片"
                            Text("$titlePrefix (${photoUris.size} 張)")
                        },
                        actions = {
                            // 一鍵開始幻燈片輪播按鈕
                            if (photoUris.isNotEmpty()) {
                                IconButton(onClick = {
                                    viewerIndex = 0
                                    isSlideshowRunning = true
                                    showViewerOverlay = true
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "播放幻燈片"
                                    )
                                }
                            }
                            // 重新整理按鈕
                            IconButton(onClick = {
                                if (hasPermission) {
                                    folderList = loadImageFolders(context)
                                    photoUris = loadDeviceImages(context, sortNewestFirst, selectedFolder)
                                } else {
                                    permissionLauncher.launch(permissions)
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "重新整理"
                                )
                            }
                            // 設定按鈕
                            IconButton(onClick = { showSettings = true }) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "設定"
                                )
                            }
                        }
                    )
                }
            ) { innerPadding ->
                when {
                    !hasPermission -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("需要相簿讀取權限才能顯示照片")
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = { permissionLauncher.launch(permissions) }) {
                                Text("授予讀取權限")
                            }
                        }
                    }
                    photoUris.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("此資料夾目前沒有照片")
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(gridColumns),
                            contentPadding = innerPadding,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(photoUris, key = { _, uri -> uri.toString() }) { index, uri ->
                                AsyncImage(
                                    model = uri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clickable {
                                            isSlideshowRunning = false
                                            viewerIndex = index
                                            showViewerOverlay = true
                                        }
                                )
                            }
                        }
                    }
                }
            }

            // --- 完整版設定面板（支援上下捲動與自動儲存） ---
            if (showSettings) {
                ModalBottomSheet(
                    onDismissRequest = { showSettings = false }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("相簿設定", style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = "自動儲存",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        // 區塊 1：資料夾來源（含子資料夾）
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("資料夾來源（包含子資料夾）", style = MaterialTheme.typography.titleSmall)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = selectedFolder == null,
                                    onClick = {
                                        selectedFolder = null
                                        saveString("selected_folder", null)
                                    },
                                    label = { Text("全部資料夾") }
                                )
                                folderList.forEach { folderPath ->
                                    FilterChip(
                                        selected = selectedFolder == folderPath,
                                        onClick = {
                                            selectedFolder = folderPath
                                            saveString("selected_folder", folderPath)
                                        },
                                        label = { Text(folderPath) }
                                    )
                                }
                            }
                        }

                        HorizontalDivider()

                        // 區塊 2：版面與排序
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("版面網格欄數", style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(2, 3, 4).forEach { count ->
                                    FilterChip(
                                        selected = gridColumns == count,
                                        onClick = {
                                            gridColumns = count
                                            saveInt("grid_columns", count)
                                        },
                                        label = { Text("$count 欄") }
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("照片排序順序", style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = sortNewestFirst,
                                    onClick = {
                                        sortNewestFirst = true
                                        saveBoolean("sort_newest_first", true)
                                    },
                                    label = { Text("最新優先") }
                                )
                                FilterChip(
                                    selected = !sortNewestFirst,
                                    onClick = {
                                        sortNewestFirst = false
                                        saveBoolean("sort_newest_first", false)
                                    },
                                    label = { Text("最舊優先") }
                                )
                            }
                        }

                        HorizontalDivider()

                        // 區塊 3：幻燈片輪播設定
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("幻燈片輪播切換間隔", style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(3, 5, 10, 30).forEach { sec ->
                                    FilterChip(
                                        selected = slideshowSeconds == sec,
                                        onClick = {
                                            slideshowSeconds = sec
                                            saveInt("slideshow_seconds", sec)
                                        },
                                        label = { Text("$sec 秒") }
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("檢視大圖時保持螢幕恆亮", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "當作桌面電子相框播放時避免螢幕休眠",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = keepScreenOn,
                                onCheckedChange = {
                                    keepScreenOn = it
                                    saveBoolean("keep_screen_on", it)
                                }
                            )
                        }

                        HorizontalDivider()

                        // 區塊 4：外觀主題
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("介面外觀主題", style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(0 to "跟隨系統", 1 to "淺色模式", 2 to "深色模式").forEach { (mode, label) ->
                                    FilterChip(
                                        selected = themeMode == mode,
                                        onClick = {
                                            themeMode = mode
                                            saveInt("theme_mode", mode)
                                        },
                                        label = { Text(label) }
                                    )
                                }
                            }
                        }

                        HorizontalDivider()

                        // 區塊 5：權限與重置管理
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                )
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("前往系統設定管理照片權限")
                        }

                        TextButton(
                            onClick = {
                                prefs.edit { clear() }
                                gridColumns = 3
                                sortNewestFirst = true
                                selectedFolder = null
                                slideshowSeconds = 5
                                keepScreenOn = true
                                themeMode = 0
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("恢復所有預設設定", color = MaterialTheme.colorScheme.error)
                        }

                        HorizontalDivider()

                        // 區塊 6：App 關於與 GitHub 線上下載更新
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "PhotoAlbum 電子相簿",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "版本：${getAppVersionInfo(context)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "作者：夜宏",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "支援自動輪播、多層資料夾讀取與本機設定記憶",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            // GitHub 檢查與下載新版本按鈕
                            FilledTonalButton(
                                onClick = {
                                    if (isCheckingUpdate || isDownloadingApk) return@FilledTonalButton
                                    isCheckingUpdate = true
                                    updateStatusMessage = "正在連線 GitHub 檢查最新版本..."
                                    coroutineScope.launch {
                                        val release = fetchLatestGitHubRelease()
                                        isCheckingUpdate = false
                                        if (release == null) {
                                            updateStatusMessage = "無法取得 GitHub 版本資訊，請確認網路連線"
                                        } else {
                                            val currentVer = getCurrentVersionName(context)
                                            if (isNewerVersion(release.tagName, currentVer)) {
                                                availableRelease = release
                                                updateStatusMessage = "發現新版本 ${release.tagName}！"
                                            } else {
                                                availableRelease = null
                                                updateStatusMessage = "目前已是最新版本 (${release.tagName})"
                                            }
                                        }
                                    }
                                },
                                enabled = !isCheckingUpdate && !isDownloadingApk,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (isCheckingUpdate) "檢查中..." else "檢查 GitHub 新版本")
                            }

                            updateStatusMessage?.let { msg ->
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            availableRelease?.let { release ->
                                Button(
                                    onClick = {
                                        if (isDownloadingApk) return@Button
                                        isDownloadingApk = true
                                        downloadProgress = 0
                                        coroutineScope.launch {
                                            downloadAndInstallApk(
                                                context = context,
                                                apkUrl = release.apkDownloadUrl,
                                                fileName = release.apkFileName,
                                                onProgress = { pct -> downloadProgress = pct },
                                                onError = { err ->
                                                    isDownloadingApk = false
                                                    updateStatusMessage = "下載失敗：$err"
                                                }
                                            )
                                            isDownloadingApk = false
                                        }
                                    },
                                    enabled = !isDownloadingApk,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        if (isDownloadingApk) {
                                            "下載更新中... $downloadProgress%"
                                        } else {
                                            "下載並安裝 ${release.tagName}"
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }

            // --- 全螢幕大圖與輪播檢視層（無閃黑淡入切換 + 上下左右手勢翻頁 + 長按返回 + 電量顯示） ---
            viewerIndex?.let { currentIndex ->
                if (photoUris.isNotEmpty() && currentIndex in photoUris.indices) {
                    var dragOffsetX by remember { mutableFloatStateOf(0f) }
                    var dragOffsetY by remember { mutableFloatStateOf(0f) }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { showViewerOverlay = !showViewerOverlay },
                                onLongClick = {
                                    isSlideshowRunning = false
                                    viewerIndex = null
                                }
                            )
                            .pointerInput(photoUris.size) {
                                detectDragGestures(
                                    onDragStart = {
                                        dragOffsetX = 0f
                                        dragOffsetY = 0f
                                    },
                                    onDragEnd = {
                                        val threshold = 80f
                                        val total = photoUris.size
                                        if (total > 1) {
                                            when {
                                                // 向左或向上滑動 -> 下一張
                                                dragOffsetX < -threshold || dragOffsetY < -threshold -> {
                                                    viewerIndex = ((viewerIndex ?: 0) + 1) % total
                                                }
                                                // 向右或向下滑動 -> 上一張
                                                dragOffsetX > threshold || dragOffsetY > threshold -> {
                                                    viewerIndex = ((viewerIndex ?: 0) - 1 + total) % total
                                                }
                                            }
                                        }
                                    }
                                ) { change, dragAmount ->
                                    change.consume()
                                    dragOffsetX += dragAmount.x
                                    dragOffsetY += dragAmount.y
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        // 使用 Crossfade 直接淡入過渡到下一張照片，避免閃爍黑框
                        Crossfade(
                            targetState = currentIndex,
                            animationSpec = tween(durationMillis = 550),
                            label = "SlideshowCrossfade"
                        ) { targetIdx ->
                            val targetUri = photoUris.getOrNull(targetIdx)
                            if (targetUri != null) {
                                AsyncImage(
                                    model = targetUri,
                                    contentDescription = "Full screen photo",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        // 右上角即時電量百分比膠囊（常駐顯示，方便桌面相框掌握電量）
                        Surface(
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = if (isCharging) "⚡ $batteryLevel%" else "🔋 $batteryLevel%",
                                color = if (batteryLevel <= 20 && !isCharging) {
                                    Color(0xFFFF6B6B)
                                } else {
                                    Color.White.copy(alpha = 0.9f)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }

                        // 下方狀態與手勢提示列（單擊螢幕可切換顯示/隱藏）
                        if (showViewerOverlay) {
                            Surface(
                                color = Color.Black.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(bottom = 24.dp)
                            ) {
                                Text(
                                    text = if (isSlideshowRunning) {
                                        "輪播中 (${currentIndex + 1} / ${photoUris.size}) · 滑動翻頁 · 長按返回"
                                    } else {
                                        "${currentIndex + 1} / ${photoUris.size} · 滑動翻頁 · 長按返回"
                                    },
                                    color = Color.White.copy(alpha = 0.85f),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// 取得所有包含照片的資料夾路徑（全版本支援自動補齊上層母資料夾）
@Suppress("DEPRECATION")
private fun loadImageFolders(context: Context): List<String> {
    val folders = linkedSetOf<String>()
    val isAndroid10Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    val columnName = if (isAndroid10Plus) {
        MediaStore.Images.Media.RELATIVE_PATH
    } else {
        MediaStore.Images.Media.DATA
    }

    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(columnName),
        null,
        null,
        null
    )?.use { cursor ->
        val colIndex = cursor.getColumnIndexOrThrow(columnName)
        while (cursor.moveToNext()) {
            val rawValue = cursor.getString(colIndex) ?: continue
            val relativeFolder = if (isAndroid10Plus) {
                rawValue.trimEnd('/')
            } else {
                val parentDir = File(rawValue).parent ?: continue
                parentDir.replaceFirst(Regex("^/storage/emulated/\\d+/"), "").trimEnd('/')
            }

            if (relativeFolder.isNotBlank()) {
                val parts = relativeFolder.split("/")
                var currentPath = ""
                for (part in parts) {
                    currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
                    folders.add(currentPath)
                }
            }
        }
    }
    return folders.sorted()
}

// 查詢本機圖片（全版本支援連同子資料夾一起讀取）
@Suppress("DEPRECATION")
private fun loadDeviceImages(
    context: Context,
    newestFirst: Boolean,
    folderPath: String?
): List<Uri> {
    val imageList = mutableListOf<Uri>()
    val projection = arrayOf(MediaStore.Images.Media._ID)
    val orderDirection = if (newestFirst) "DESC" else "ASC"
    val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} $orderDirection"

    val isAndroid10Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    val selection = when {
        folderPath == null -> null
        isAndroid10Plus -> "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        else -> "${MediaStore.Images.Media.DATA} LIKE ?"
    }
    val selectionArgs = when {
        folderPath == null -> null
        isAndroid10Plus -> arrayOf("$folderPath/%")
        else -> arrayOf("%/$folderPath/%")
    }

    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        selectionArgs,
        sortOrder
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val contentUri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                id
            )
            imageList.add(contentUri)
        }
    }
    return imageList
}
