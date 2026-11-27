package com.example.photoalbum

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
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
import androidx.appcompat.app.AppCompatDelegate
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.photoalbum.ui.theme.PhotoAlbumTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Suppress("DEPRECATION")
private fun applyAppLanguage(context: Context, langTag: String) {
    val targetLocale = if (langTag == "system") {
        Resources.getSystem().configuration.locales[0]
    } else {
        Locale.forLanguageTag(langTag)
    }
    Locale.setDefault(targetLocale)
    val res = context.resources
    val config = Configuration(res.configuration)
    config.setLocale(targetLocale)
    res.updateConfiguration(config, res.displayMetrics)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoAlbumApp() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences("photo_album_prefs", Context.MODE_PRIVATE) }

    // --- 讀取已儲存的語系並即時套用 ---
    var selectedLangTag by remember {
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        val initialTag = when {
            currentLocales.isEmpty -> prefs.getString("app_language", "system") ?: "system"
            currentLocales.toLanguageTags().startsWith("zh", ignoreCase = true) -> "zh-TW"
            currentLocales.toLanguageTags().startsWith("en", ignoreCase = true) -> "en"
            else -> "system"
        }
        applyAppLanguage(context, initialTag)
        mutableStateOf(initialTag)
    }

    key(selectedLangTag) {
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
                        ContextCompat.checkSelfPermission(
                            context,
                            it
                        ) == PackageManager.PERMISSION_GRANTED
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
                val updatedPhotos =
                    MediaRepository.loadDeviceImages(context, sortNewestFirst, selectedFolder)
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
                                val titlePrefix = selectedFolder?.substringAfterLast("/")
                                    ?: stringResource(R.string.all_photos)
                                Text(
                                    stringResource(
                                        R.string.photo_count_format,
                                        titlePrefix,
                                        photoUris.size
                                    )
                                )
                            },
                            actions = {
                                if (photoUris.isNotEmpty()) {
                                    IconButton(onClick = {
                                        val resumeIdx = savedLastIndex.coerceIn(0, photoUris.lastIndex)
                                        updateViewerIndex(resumeIdx)
                                        isSlideshowRunning = true
                                        showViewerOverlay = true
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = stringResource(R.string.cd_resume_slideshow)
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
                                        contentDescription = stringResource(R.string.cd_refresh)
                                    )
                                }
                                IconButton(onClick = { showSettings = true }) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = stringResource(R.string.cd_settings)
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
                                Text(stringResource(R.string.permission_required_message))
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(onClick = { permissionLauncher.launch(permissions) }) {
                                    Text(stringResource(R.string.grant_permission))
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
                                Text(stringResource(R.string.empty_folder_message))
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
                                itemsIndexed(
                                    photoUris,
                                    key = { _, uri -> uri.toString() }) { index, uri ->
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
                                Text(
                                    text = stringResource(R.string.settings_title),
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = stringResource(R.string.auto_saved),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            // 區塊 1：資料夾來源
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = stringResource(R.string.section_folder_source),
                                    style = MaterialTheme.typography.titleSmall
                                )
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
                                        label = { Text(stringResource(R.string.all_folders)) }
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
                                Text(
                                    text = stringResource(R.string.section_grid_columns),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(2, 3, 4).forEach { count ->
                                        FilterChip(
                                            selected = gridColumns == count,
                                            onClick = {
                                                gridColumns = count
                                                saveInt("grid_columns", count)
                                            },
                                            label = {
                                                Text(
                                                    stringResource(
                                                        R.string.columns_format,
                                                        count
                                                    )
                                                )
                                            }
                                        )
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = stringResource(R.string.section_sort_order),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = sortNewestFirst,
                                        onClick = {
                                            sortNewestFirst = true
                                            saveBoolean("sort_newest_first", true)
                                        },
                                        label = { Text(stringResource(R.string.sort_newest_first)) }
                                    )
                                    FilterChip(
                                        selected = !sortNewestFirst,
                                        onClick = {
                                            sortNewestFirst = false
                                            saveBoolean("sort_newest_first", false)
                                        },
                                        label = { Text(stringResource(R.string.sort_oldest_first)) }
                                    )
                                }
                            }

                            HorizontalDivider()

                            // 區塊 3：幻燈片輪播設定
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = stringResource(R.string.section_slideshow_interval),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(3, 5, 10, 30).forEach { sec ->
                                        FilterChip(
                                            selected = slideshowSeconds == sec,
                                            onClick = {
                                                slideshowSeconds = sec
                                                saveInt("slideshow_seconds", sec)
                                            },
                                            label = {
                                                Text(
                                                    stringResource(
                                                        R.string.seconds_format,
                                                        sec
                                                    )
                                                )
                                            }
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
                                    Text(
                                        text = stringResource(R.string.keep_screen_on_title),
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        text = stringResource(R.string.keep_screen_on_desc),
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
                                    Text(
                                        stringResource(
                                            R.string.reset_last_position_format,
                                            savedLastIndex + 1
                                        )
                                    )
                                }
                            }

                            // 區塊 4-2：應用程式語言（即時無閃爍切換）
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = stringResource(R.string.section_language),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val langOptions = listOf(
                                        "system" to stringResource(R.string.lang_system),
                                        "zh-TW" to stringResource(R.string.lang_zh_tw),
                                        "en" to stringResource(R.string.lang_en)
                                    )
                                    langOptions.forEach { (tag, label) ->
                                        FilterChip(
                                            selected = selectedLangTag == tag,
                                            onClick = {
                                                if (selectedLangTag != tag) {
                                                    applyAppLanguage(context, tag)
                                                    selectedLangTag = tag
                                                    saveString("app_language", tag)
                                                    val localeList = if (tag == "system") {
                                                        LocaleListCompat.getEmptyLocaleList()
                                                    } else {
                                                        LocaleListCompat.forLanguageTags(tag)
                                                    }
                                                    AppCompatDelegate.setApplicationLocales(localeList)
                                                }
                                            },
                                            label = { Text(label) }
                                        )
                                    }
                                }
                            }

                            HorizontalDivider()

                            // 區塊 4：外觀主題
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = stringResource(R.string.section_theme),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val themeOptions = listOf(
                                        0 to stringResource(R.string.theme_system),
                                        1 to stringResource(R.string.theme_light),
                                        2 to stringResource(R.string.theme_dark)
                                    )
                                    themeOptions.forEach { (mode, label) ->
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
                                Text(stringResource(R.string.manage_permissions_system))
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
                                    applyAppLanguage(context, "system")
                                    selectedLangTag = "system"
                                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = stringResource(R.string.reset_all_settings),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            HorizontalDivider()

                            // 區塊 6：APP 資訊與 GitHub 線上下載更新
                            val packageInfo = remember {
                                context.packageManager.getPackageInfo(context.packageName, 0)
                            }
                            val versionName = packageInfo.versionName ?: "1.0"
                            val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = stringResource(R.string.section_about_app),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = stringResource(R.string.app_name),
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Text(
                                                text = "v$versionName ($versionCode)",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Text(
                                            text = "Android Jetpack Compose • Material 3",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = stringResource(R.string.developer_info),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            val strUpdateConnecting = stringResource(R.string.update_connecting)
                            val strUpdateFetchFailed = stringResource(R.string.update_fetch_failed)
                            val strUpdateFoundFormat = stringResource(R.string.update_found_format)
                            val strUpdateUpToDateFormat =
                                stringResource(R.string.update_up_to_date_format)
                            val strUpdateDownloadFailedFormat =
                                stringResource(R.string.update_download_failed_format)

                            FilledTonalButton(
                                onClick = {
                                    if (isCheckingUpdate || isDownloadingApk) return@FilledTonalButton
                                    isCheckingUpdate = true
                                    updateStatusMessage = strUpdateConnecting
                                    coroutineScope.launch {
                                        val release = AppUpdater.fetchLatestGitHubRelease()
                                        isCheckingUpdate = false
                                        if (release == null) {
                                            updateStatusMessage = strUpdateFetchFailed
                                        } else {
                                            val latestVer =
                                                MediaRepository.getCurrentVersionName(context)
                                            if (AppUpdater.isNewerVersion(
                                                    release.tagName,
                                                    latestVer
                                                )
                                            ) {
                                                availableRelease = release
                                                updateStatusMessage =
                                                    String.format(strUpdateFoundFormat, release.tagName)
                                            } else {
                                                availableRelease = null
                                                updateStatusMessage = String.format(
                                                    strUpdateUpToDateFormat,
                                                    release.tagName
                                                )
                                            }
                                        }
                                    }
                                },
                                enabled = !isCheckingUpdate && !isDownloadingApk,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (isCheckingUpdate) {
                                        stringResource(R.string.checking_update)
                                    } else {
                                        stringResource(R.string.check_github_update)
                                    }
                                )
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
                                                    updateStatusMessage = String.format(
                                                        strUpdateDownloadFailedFormat,
                                                        err
                                                    )
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
                                            stringResource(
                                                R.string.downloading_update_format,
                                                downloadProgress
                                            )
                                        } else {
                                            stringResource(
                                                R.string.download_and_install_format,
                                                release.tagName
                                            )
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(24.dp))
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
}
