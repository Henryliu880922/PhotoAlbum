package com.example.photoalbum

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.photoalbum.ui.theme.PhotoAlbumTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoAlbumApp() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences("photo_album_prefs", Context.MODE_PRIVATE) }

    // --- 讀取已儲存的設定值與上次播放位置 ---
    var gridColumns by remember { mutableIntStateOf(prefs.getInt("grid_columns", 3)) }
    var sortNewestFirst by remember { mutableStateOf(prefs.getBoolean("sort_newest_first", true)) }
    var selectedFolder by remember { mutableStateOf(prefs.getString("selected_folder", null)) }
    var slideshowSeconds by remember { mutableIntStateOf(prefs.getInt("slideshow_seconds", 5)) }
    var keepScreenOn by remember { mutableStateOf(prefs.getBoolean("keep_screen_on", true)) }
    var themeMode by remember { mutableIntStateOf(prefs.getInt("theme_mode", 0)) }
    var savedLastIndex by remember { mutableIntStateOf(prefs.getInt("last_viewer_index", 0)) }

    fun saveInt(key: String, value: Int) = prefs.edit { putInt(key, value) }
    fun saveBoolean(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }
    fun saveString(key: String, value: String?) = prefs.edit { putString(key, value) }

    val useDarkTheme = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }

    PhotoAlbumTheme(darkTheme = useDarkTheme) {
        var photoUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
        var folderList by remember { mutableStateOf<List<String>>(emptyList()) }
        var showSettings by remember { mutableStateOf(false) }

        var viewerIndex by rememberSaveable { mutableStateOf<Int?>(null) }
        var isSlideshowRunning by rememberSaveable { mutableStateOf(false) }
        var showViewerOverlay by rememberSaveable { mutableStateOf(true) }

        // 統一更新目前檢視索引並同步記憶到 SharedPreferences
        fun updateViewerIndex(newIndex: Int?) {
            viewerIndex = newIndex
            if (newIndex != null) {
                savedLastIndex = newIndex
                saveInt("last_viewer_index", newIndex)
            }
        }

        // GitHub 更新檢查狀態
        var isCheckingUpdate by remember { mutableStateOf(false) }
        var updateStatusMessage by remember { mutableStateOf<String?>(null) }
        var availableRelease by remember { mutableStateOf<GitHubReleaseInfo?>(null) }
        var isDownloadingApk by remember { mutableStateOf(false) }
        var downloadProgress by remember { mutableIntStateOf(0) }

        // 控制全螢幕檢視或輪播時保持螢幕恆亮，並允許四向感應器自動旋轉
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

        // 自動幻燈片輪播計時器
        LaunchedEffect(isSlideshowRunning, viewerIndex, slideshowSeconds, photoUris.size) {
            if (isSlideshowRunning && viewerIndex != null && photoUris.isNotEmpty()) {
                delay(slideshowSeconds.seconds)
                updateViewerIndex(((viewerIndex ?: 0) + 1) % photoUris.size)
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

        fun refreshMediaLibrary() {
            if (!hasPermission) return
            val scanDirs = arrayOf(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
            )
            MediaScannerConnection.scanFile(context, scanDirs, null, null)

            val updatedFolders = MediaRepository.loadImageFolders(context)
            folderList = updatedFolders
            if (selectedFolder != null && selectedFolder !in updatedFolders) {
                selectedFolder = null
                saveString("selected_folder", null)
            }
            val updatedPhotos = MediaRepository.loadDeviceImages(context, sortNewestFirst, selectedFolder)
            photoUris = updatedPhotos

            if (savedLastIndex >= updatedPhotos.size) {
                savedLastIndex = 0
                saveInt("last_viewer_index", 0)
            }
            val currentIdx = viewerIndex
            if (currentIdx != null) {
                if (updatedPhotos.isEmpty()) {
                    isSlideshowRunning = false
                    viewerIndex = null
                } else if (currentIdx >= updatedPhotos.size) {
                    updateViewerIndex(updatedPhotos.lastIndex)
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
                            if (photoUris.isNotEmpty()) {
                                IconButton(onClick = {
                                    // 從上次記憶的進度位置繼續播放
                                    val resumeIdx = savedLastIndex.coerceIn(0, photoUris.lastIndex)
                                    updateViewerIndex(resumeIdx)
                                    isSlideshowRunning = true
                                    showViewerOverlay = true
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "繼續播放幻燈片"
                                    )
                                }
                            }
                            IconButton(onClick = {
                                if (hasPermission) {
                                    refreshMediaLibrary()
                                } else {
                                    permissionLauncher.launch(permissions)
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "重新整理"
                                )
                            }
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
                                            updateViewerIndex(index)
                                            showViewerOverlay = true
                                        }
                                )
                            }
                        }
                    }
                }
            }

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

                        // 區塊 1：資料夾來源
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
                                        savedLastIndex = 0
                                        saveInt("last_viewer_index", 0)
                                    },
                                    label = { Text("全部資料夾") }
                                )
                                folderList.forEach { folderPath ->
                                    FilterChip(
                                        selected = selectedFolder == folderPath,
                                        onClick = {
                                            selectedFolder = folderPath
                                            saveString("selected_folder", folderPath)
                                            savedLastIndex = 0
                                            saveInt("last_viewer_index", 0)
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

                        if (photoUris.isNotEmpty() && savedLastIndex > 0) {
                            OutlinedButton(
                                onClick = {
                                    savedLastIndex = 0
                                    saveInt("last_viewer_index", 0)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("重置記憶播放位置（目前記憶於第 ${savedLastIndex + 1} 張）")
                            }
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
                                savedLastIndex = 0
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
                                text = "電子相簿 (PhotoAlbum)",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "版本：${MediaRepository.getAppVersionInfo(context)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "作者：夜宏",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "支援無縫輪播、即時時鐘電量、位置記憶與線上更新",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            FilledTonalButton(
                                onClick = {
                                    if (isCheckingUpdate || isDownloadingApk) return@FilledTonalButton
                                    isCheckingUpdate = true
                                    updateStatusMessage = "正在連線 GitHub 檢查最新版本..."
                                    coroutineScope.launch {
                                        val release = AppUpdater.fetchLatestGitHubRelease()
                                        isCheckingUpdate = false
                                        if (release == null) {
                                            updateStatusMessage = "無法取得 GitHub 版本資訊，請確認網路連線"
                                        } else {
                                            val currentVer = MediaRepository.getCurrentVersionName(context)
                                            if (AppUpdater.isNewerVersion(release.tagName, currentVer)) {
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
                                            AppUpdater.downloadAndInstallApk(
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

            // 全螢幕無縫大圖與輪播檢視層
            viewerIndex?.let { currentIndex ->
                if (photoUris.isNotEmpty() && currentIndex in photoUris.indices) {
                    SlideshowViewer(
                        photoUris = photoUris,
                        currentIndex = currentIndex,
                        isSlideshowRunning = isSlideshowRunning,
                        showOverlay = showViewerOverlay,
                        onToggleOverlay = { showViewerOverlay = !showViewerOverlay },
                        onChangeIndex = { nextIdx -> updateViewerIndex(nextIdx) },
                        onExitViewer = {
                            isSlideshowRunning = false
                            viewerIndex = null
                        }
                    )
                }
            }
        }
    }
}
