package com.transmusic.converter.engine

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/**
 * ffmpeg 执行层（FFmpegKit）。
 * 只负责「把命令行跑起来 + 回报进度 + 支持取消」，参数构造在 CommandBuilder。
 */
object FfmpegEngine {

    data class Result(
        val ok: Boolean,
        val canceled: Boolean,
        val message: String,
        val elapsedMs: Long,
    )

    /** 引擎版本，用于界面显示。 */
    fun version(): String = try {
        FFmpegKitConfig.getFFmpegVersion().orEmpty().ifBlank { "ffmpeg" }
    } catch (e: Throwable) {
        "ffmpeg"
    }

    /**
     * 异步执行一条 ffmpeg 命令并在协程中等待结果。
     * 协程取消 → 结束对应的 ffmpeg 会话。
     */
    suspend fun run(
        args: List<String>,
        durationMs: Long,
        onProgress: (Float) -> Unit = {},
    ): Result {
        val command = CommandBuilder.toCommandLine(args)
        val started = System.currentTimeMillis()
        return suspendCancellableCoroutine { cont ->
            val sessionId = AtomicLong(-1L)
            val session = FFmpegKit.executeAsync(
                command,
                { s ->
                    val rc = s.returnCode
                    val ok = ReturnCode.isSuccess(rc)
                    val canceled = ReturnCode.isCancel(rc)
                    val message = when {
                        ok -> "完成"
                        canceled -> "已取消"
                        else -> {
                            val trace = s.failStackTrace?.trim()
                                ?.lineSequence()?.lastOrNull { it.isNotBlank() }
                            val tail = s.output?.lineSequence()?.filter { it.isNotBlank() }
                                ?.toList()?.takeLast(2)?.joinToString(" ")
                            trace ?: tail ?: "ffmpeg 失败（返回码 $rc）"
                        }
                    }
                    if (cont.isActive) {
                        cont.resume(Result(ok, canceled, message, System.currentTimeMillis() - started))
                    }
                },
                { _ -> /* 日志：需要排查时可在此打印 */ },
                { statistics ->
                    val t = statistics.time
                    if (durationMs > 0 && t > 0) {
                        onProgress((t.toFloat() / durationMs * 100f).coerceIn(0f, 99f))
                    }
                },
            )
            sessionId.set(session.sessionId)
            cont.invokeOnCancellation {
                val id = sessionId.get()
                if (id >= 0) {
                    runCatching { FFmpegKit.cancel(id) }
                }
            }
        }
    }

    /** 结束全部正在运行的会话（取消按钮用）。 */
    fun cancelAll() {
        runCatching { FFmpegKit.cancel() }
    }
}
