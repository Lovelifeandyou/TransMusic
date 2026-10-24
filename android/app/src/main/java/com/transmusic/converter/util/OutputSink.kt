package com.transmusic.converter.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

/** 输出位置：系统音乐库，或用户用 SAF 选的文件夹。 */
sealed class Destination {
    data object MusicLibrary : Destination()
    data class Tree(val uri: Uri, val label: String) : Destination()
}

object OutputSink {

    /**
     * 把临时文件投递到用户可见位置。
     * @return 展示用的相对路径，例如 "Music/TransMusic/夜航船.mp3"
     */
    fun publish(
        context: Context,
        temp: File,
        displayName: String,
        mime: String,
        dest: Destination,
    ): String {
        if (!temp.exists() || temp.length() == 0L) throw IOException("输出文件为空")
        return when (dest) {
            is Destination.MusicLibrary -> publishToMusicLibrary(context, temp, displayName, mime)
            is Destination.Tree -> publishToTree(context, temp, displayName, mime, dest.uri)
        }
    }

    // ---------------- 系统音乐库 ----------------
    private fun publishToMusicLibrary(
        context: Context,
        temp: File,
        displayName: String,
        mime: String,
    ): String {
        val name = uniqueMusicName(context, displayName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$MUSIC_FOLDER/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法在音乐库创建文件")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    temp.inputStream().use { it.copyTo(out, 1 shl 16) }
                } ?: throw IOException("无法写入音乐库")
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            return "$MUSIC_FOLDER/$name"
        }

        // Android 9 及以下：直接写公共音乐目录（需 WRITE_EXTERNAL_STORAGE）
        @Suppress("DEPRECATION")
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "TransMusic",
        )
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建 Music/TransMusic 目录")
        val target = uniqueFile(dir, name)
        temp.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        return "$MUSIC_FOLDER/${target.name}"
    }

    // ---------------- 自选文件夹（SAF） ----------------
    private fun publishToTree(
        context: Context,
        temp: File,
        displayName: String,
        mime: String,
        treeUri: Uri,
    ): String {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("所选目录不可访问，请重新选择")
        val name = uniqueDocName(root, displayName)
        val doc = root.createFile(mime, name) ?: throw IOException("无法在所选目录创建文件")
        try {
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                temp.inputStream().use { it.copyTo(out, 1 shl 16) }
            } ?: throw IOException("无法写入所选目录")
        } catch (e: Exception) {
            runCatching { doc.delete() }
            throw e
        }
        return "${root.name ?: "所选文件夹"}/${doc.name ?: name}"
    }

    // ---------------- 重名处理 ----------------
    private const val MUSIC_FOLDER = "Music/TransMusic"

    private fun uniqueMusicName(context: Context, name: String): String {
        if (!musicNameExists(context, name)) return name
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        for (i in 1..999) {
            val candidate = if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"
            if (!musicNameExists(context, candidate)) return candidate
        }
        return name
    }

    private fun musicNameExists(context: Context, name: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        return try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf(name, "$MUSIC_FOLDER/"),
                null,
            )?.use { it.count > 0 } ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        for (i in 1..999) {
            candidate = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            if (!candidate.exists()) return candidate
        }
        return candidate
    }

    private fun uniqueDocName(root: DocumentFile, name: String): String {
        if (root.findFile(name) == null) return name
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        for (i in 1..999) {
            val candidate = if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"
            if (root.findFile(candidate) == null) return candidate
        }
        return name
    }
}
