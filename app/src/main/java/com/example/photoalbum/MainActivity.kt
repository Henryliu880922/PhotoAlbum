package com.example.photoalbum

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.example.photoalbum.ui.theme.PhotoAlbumTheme
import kotlinx.coroutines.delay
import androidx.core.content.edit
import kotlin.time.Duration.Companion.seconds
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoAlbumApp()
        }
    }
}
// 讀取目前 App 的版本名稱與版本代碼（對應 build.gradle.kts 的設定）
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoAlbumApp() {
    val context = LocalContext.current
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
        var photoUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
        var folderList by remember { mutableStateOf<List<String>>(emptyList()) }
        var showSettings by remember { mutableStateOf(false) }

        // 大圖與輪播控制狀態
        var viewerIndex by remember { mutableStateOf<Int?>(null) }
        var isSlideshowRunning by remember { mutableStateOf(false) }

        // 控制全螢幕檢視或輪播時是否保持螢幕恆亮
        DisposableEffect(viewerIndex, keepScreenOn) {
            val window = (context as? Activity)?.window
            if (viewerIndex != null && keepScreenOn) {
                window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
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

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            hasPermission = result.values.any { it }
            if (hasPermission) {
                folderList = loadImageFolders(context)
                photoUris = loadDeviceImages(context, sortNewestFirst, selectedFolder)
            }
        }

        LaunchedEffect(hasPermission, sortNewestFirst, selectedFolder) {
            if (hasPermission) {
                folderList = loadImageFolders(context)
                if (selectedFolder != null && selectedFolder !in folderList) {
                    selectedFolder = null
                    saveString("selected_folder", null)
                }
                photoUris = loadDeviceImages(context, sortNewestFirst, selectedFolder)
            } else {
                permissionLauncher.launch(permissions)
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

                        // 區塊 6：App 關於與版本資訊
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
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
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }

            // --- 全螢幕大圖與輪播檢視層 ---
            viewerIndex?.let { currentIndex ->
                val currentUri = photoUris.getOrNull(currentIndex)
                if (currentUri != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .clickable {
                                isSlideshowRunning = false
                                viewerIndex = null
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = currentUri,
                            contentDescription = "Full screen photo",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )

                        // 左下角顯示目前張數與輪播狀態提示
                        Text(
                            text = if (isSlideshowRunning) {
                                "輪播中 (${currentIndex + 1} / ${photoUris.size}) · 點擊螢幕結束"
                            } else {
                                "${currentIndex + 1} / ${photoUris.size} · 點擊螢幕關閉"
                            },
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 32.dp)
                        )
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
                val parentDir = java.io.File(rawValue).parent ?: continue
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
