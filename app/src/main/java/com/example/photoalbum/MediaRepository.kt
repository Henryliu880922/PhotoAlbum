package com.example.photoalbum

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

object MediaRepository {

    fun getCurrentVersionName(context: Context): String {
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

    fun getAppVersionInfo(context: Context): String {
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

    @Suppress("DEPRECATION")
    fun loadImageFolders(context: Context): List<String> {
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

    @Suppress("DEPRECATION")
    fun loadDeviceImages(
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
}
