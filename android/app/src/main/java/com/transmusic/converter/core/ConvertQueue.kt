package com.transmusic.converter.core

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import com.transmusic.converter.engine.AudioProbe
import com.transmusic.converter.engine.CommandBuilder
import com.transmusic.converter.engine.ConvertSettings
import com.transmusic.converter.engine.FfmpegEngine
import com.transmusic.converter.model.AudioExts
import com.transmusic.converter.model.AudioFormat
import com.transmusic.converter.util.Destination
import com.transmusic.converter.util.OutputSink
import com.transmusic.converter.util.WorkFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 批量转换队列：
 *  - 并发 N 个任务（信号量限流）
 *  - 每个任务：Uri → 缓存文件 → ffmpeg → 投递到音乐库/自选目录 → 清理
 *  - 进度、状态、日志全部通过 StateFlow 上报给界面
 */
class ConvertQueue(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    val jobs: StateFlow<List<Job>> = _jobs.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _status = MutableStateFlow("就绪：添加音频后即可开始")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val ids = AtomicLong(0)
    private var workerCount = 2
    private var runningJob: CoroutineJob? = null
    private var startedAt = 0L

    val isRunning: Boolean get() = _running.value

    // ------------------------------------------------------------------ 列表维护
    fun addUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _status.value = "正在分析 ${uris.size} 个文件…"
        scope.launch {
            val added = mutableListOf<Job>()
            withContext(Dispatchers.IO) {
                for (uri in uris) {
                    val name = queryName(uri) ?: continue
                    if (!AudioExts.isAudioLike(name)) continue
                    if (_jobs.value.any { it.uri == uri }) continue
                    val info = AudioProbe.probe(context, uri, name)
                    added += Job(
                        id = ids.incrementAndGet(),
                        uri = uri,
                        displayName = name,
                        sizeBytes = querySize(uri),
                        info = info,
                    )
                }
            }
            if (added.isEmpty()) {
                _status.value = "没有可识别的音频文件"
                return@launch
            }
            _jobs.update { it + added }
            _status.value = "已添加 ${added.size} 个文件"
            log("添加 ${added.size} 个文件")
        }
    }

    fun remove(id: Long) {
        _jobs.update { list -> list.filterNot { it.id == id } }
    }

    fun clearAll() {
        if (_running.value) return
        _jobs.value = emptyList()
        _status.value = "已清空"
    }

    fun clearFinished() {
        _jobs.update { list -> list.filter { it.status == JobStatus.RUNNING || it.status == JobStatus.WAITING } }
    }

    // ------------------------------------------------------------------ 开始 / 取消
    fun start(
        fmt: AudioFormat,
        settings: ConvertSettings,
        workers: Int,
        destination: Destination,
        template: String = "{name}",
    ) {
        if (_running.value) return
        val targets = _jobs.value.filter { it.status != JobStatus.RUNNING && it.info.ok }
        if (targets.isEmpty()) {
            _status.value = if (_jobs.value.isEmpty()) "请先添加音频文件" else "没有可转换的文件"
            return
        }
        workerCount = workers.coerceIn(1, 8)
        startedAt = SystemClock.elapsedRealtime()
        targets.forEach { t ->
            update(t.id) {
                it.copy(status = JobStatus.WAITING, progress = 0f, message = "", elapsedMs = 0L)
            }
        }
        _running.value = true
        val s = settings.copy(formatKey = fmt.key)
        log("开始转换 ${targets.size} 个文件 → ${fmt.label}（并发 $workerCount）")
        _status.value = "转换中 0/${targets.size}"

        runningJob = scope.launch {
            try {
                val sem = Semaphore(workerCount)
                val children = targets.map { job ->
                    launch { sem.withPermit { runOne(job, fmt, s, destination, template) } }
                }
                children.forEach { it.join() }
            } catch (_: CancellationException) {
                // 用户取消：清理在 finally 里统一做
            } finally {
                _jobs.value.filter { it.status == JobStatus.WAITING }.forEach { j ->
                    update(j.id) { it.copy(status = JobStatus.CANCELED, message = "已取消") }
                }
                val all = _jobs.value
                val ok = all.count { it.status == JobStatus.DONE }
                val failed = all.count { it.status == JobStatus.FAILED }
                val canceled = all.count { it.status == JobStatus.CANCELED }
                val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000
                _running.value = false
                _status.value = "完成 $ok · 失败 $failed · 取消 $canceled · 用时 ${elapsed}s"
                log("全部结束：成功 $ok，失败 $failed，取消 $canceled，用时 $elapsed 秒")
            }
        }
    }

    fun cancel() {
        if (!_running.value) return
        log("已请求取消，正在终止 ffmpeg…")
        FfmpegEngine.cancelAll()
        runningJob?.cancel()
    }

    // ------------------------------------------------------------------ 单个任务
    private suspend fun runOne(
        job: Job,
        fmt: AudioFormat,
        settings: ConvertSettings,
        destination: Destination,
        template: String,
    ) {
        val work = WorkFiles(context, job.id)
        update(job.id) { it.copy(status = JobStatus.RUNNING, progress = 0f, message = "") }
        val outName = outputName(job, fmt, template)
        log("▶ ${job.displayName} → $outName")

        try {
            val srcPath = withContext(Dispatchers.IO) { work.stageInput(job.uri, job.displayName) }
            val outPath = work.outputFile(fmt.ext)
            val args = CommandBuilder.build(srcPath, outPath, fmt, settings, job.info)

            var lastPct = -1f
            val result = FfmpegEngine.run(args, job.info.durationMs) { pct ->
                if (pct - lastPct >= 0.5f) {
                    lastPct = pct
                    update(job.id) { it.copy(progress = pct) }
                }
            }

            when {
                result.ok -> {
                    val shown = withContext(Dispatchers.IO) {
                        OutputSink.publish(
                            context,
                            File(outPath),
                            outName,
                            AudioProbe.mimeFor(fmt.ext),
                            destination,
                        )
                    }
                    update(job.id) {
                        it.copy(
                            status = JobStatus.DONE,
                            progress = 100f,
                            message = shown,
                            outName = outName,
                            elapsedMs = result.elapsedMs,
                        )
                    }
                    log("✔ ${job.displayName} → $shown（${result.elapsedMs / 1000.0} 秒）")
                }
                result.canceled -> {
                    update(job.id) { it.copy(status = JobStatus.CANCELED, message = "已取消") }
                    log("× ${job.displayName} 已取消")
                }
                else -> {
                    update(job.id) { it.copy(status = JobStatus.FAILED, message = result.message) }
                    log("✘ ${job.displayName} 失败：${result.message}")
                }
            }
        } catch (ce: CancellationException) {
            update(job.id) { it.copy(status = JobStatus.CANCELED, message = "已取消") }
            throw ce
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            update(job.id) { it.copy(status = JobStatus.FAILED, message = msg) }
            log("✘ ${job.displayName} 失败：$msg")
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                withContext(Dispatchers.IO) {
                    work.cleanupOutputs()
                    work.cleanup()
                }
            }
        }
    }

    // ------------------------------------------------------------------ 工具
    /** 输出文件名模板：{name} {artist} {title} {album} {index} {ext} */
    private fun outputName(job: Job, fmt: AudioFormat, template: String): String {
        val values = mapOf(
            "name" to job.title,
            "artist" to job.info.artist.ifBlank { "" },
            "title" to job.info.title.ifBlank { job.title },
            "album" to job.info.album.ifBlank { "" },
            "index" to "%02d".format(_jobs.value.indexOfFirst { it.id == job.id } + 1),
            "ext" to fmt.ext,
        )
        var text = template.ifBlank { "{name}" }
        values.forEach { (k, v) -> text = text.replace("{$k}", v) }
        text = sanitize(text)
        return "$text.${fmt.ext}"
    }

    private fun sanitize(name: String): String {
        val cleaned = name.map { c -> if (c in "<>:\"/\\|?*" || c.code < 32) '_' else c }
            .joinToString("")
            .trim()
            .trim('.')
        return cleaned.take(120).ifBlank { "output" }
    }

    private fun update(id: Long, transform: (Job) -> Job) {
        _jobs.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    private fun log(line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        _logs.update { (it + "[$stamp] $line").takeLast(300) }
    }

    private fun queryName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    private fun querySize(uri: Uri): Long = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L } ?: 0L
    } catch (_: Exception) {
        0L
    }
}
