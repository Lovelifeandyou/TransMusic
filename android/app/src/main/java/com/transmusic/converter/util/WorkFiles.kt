package com.transmusic.converter.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * 单任务的临时文件管理。
 * ffmpeg 需要真实文件路径，而 SAF 给的只是 content:// Uri，所以要先把源文件落到缓存目录。
 * 文件名一律用 in_/out_ + 任务号，保证不含空格等字符，命令行拼接绝对安全。
 */
class WorkFiles(private val context: Context, private val jobId: Long) {

    private val inDir: File = File(context.cacheDir, "tm_in").apply { mkdirs() }
    private val outDir: File = File(context.cacheDir, "tm_out").apply { mkdirs() }
    private var stagedFile: File? = null

    /** 复制 picker 选中的源文件到缓存目录，返回可被 ffmpeg 读取的路径。 */
    fun stageInput(uri: Uri, displayName: String): String {
        val rawExt = displayName.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }
        val ext = if (rawExt.isEmpty()) "bin" else rawExt.take(8)
        val dst = File(inDir, "in_$jobId.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
        } ?: throw IOException("无法读取源文件（权限可能已失效）")
        stagedFile = dst
        return dst.absolutePath
    }

    /** 输出临时路径（同任务号，避免并发冲突）。 */
    fun outputFile(ext: String): String {
        val file = File(outDir, "out_$jobId.$ext")
        if (file.exists()) file.delete()
        return file.absolutePath
    }

    fun cleanup() {
        stagedFile?.let { runCatching { it.delete() } }
        stagedFile = null
        runCatching { File(outDir, "out_$jobId.tmp").delete() }
    }

    /** 任务结束统一清掉本任务在 out 目录留下的所有产物（投递后调用）。 */
    fun cleanupOutputs() {
        runCatching {
            outDir.listFiles { f -> f.name.startsWith("out_$jobId.") }?.forEach { it.delete() }
        }
    }
}
