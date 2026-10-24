package com.transmusic.converter.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.transmusic.converter.core.ConvertViewModel
import com.transmusic.converter.core.Job
import com.transmusic.converter.core.JobStatus
import com.transmusic.converter.engine.FfmpegEngine
import com.transmusic.converter.ui.theme.Motion
import com.transmusic.converter.util.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * MD3E 主界面：大标题 + 输出摘要卡（大色块）+ 任务卡列表 + 底部主按钮 + 设置 BottomSheet。
 */
@Composable
fun ConvertScreen(viewModel: ConvertViewModel) {
    val context = LocalContext.current
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val statusLine by viewModel.status.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    val state = remember { ConvertSettingsState(viewModel.savedSettings) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showLogs by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Job?>(null) }
    var treeUri by rememberSaveable { mutableStateOf<String?>(null) }
    var treeName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingStart by remember { mutableStateOf(false) }
    var engineVersion by remember { mutableStateOf("…") }

    LaunchedEffect(Unit) {
        engineVersion = withContext(Dispatchers.IO) { FfmpegEngine.version() }
    }

    val destination: Destination = remember(treeUri, treeName) {
        val saved = treeUri
        if (saved.isNullOrBlank()) {
            Destination.MusicLibrary
        } else {
            Destination.Tree(Uri.parse(saved), treeName ?: "自选文件夹")
        }
    }

    fun doStart() {
        viewModel.start(
            format = state.format,
            settings = state.toSettings(),
            workers = state.workers,
            qualityIndex = state.qualityIndex,
            destination = destination,
        )
    }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addUris(uris)
    }

    val pickTree = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            treeUri = uri.toString()
            treeName = DocumentFile.fromTreeUri(context, uri)?.name ?: "自选文件夹"
        }
    }

    val requestWrite = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingStart) doStart()
        pendingStart = false
        if (!granted) toast(context, "没有存储权限，可改选「自选文件夹」")
    }

    fun startConversion() {
        if (running) return
        val needsLegacyWrite = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            destination is Destination.MusicLibrary &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsLegacyWrite) {
            pendingStart = true
            requestWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            doStart()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ScreenHeader(
                engineVersion = engineVersion,
                showLogs = showLogs,
                onAdd = {
                    runCatching {
                        pickFiles.launch(arrayOf("audio/*", "video/*", "application/ogg", "*/*"))
                    }.onFailure { toast(context, "无法打开文件选择器：${it.message}") }
                },
                onToggleLogs = { showLogs = !showLogs },
                onClear = {
                    if (running) toast(context, "正在转换，请先取消") else viewModel.clearAll()
                },
            )
        },
        bottomBar = {
            ActionBar(
                running = running,
                canStart = jobs.isNotEmpty(),
                onStart = { startConversion() },
                onCancel = { viewModel.cancel() },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutputSummaryCard(
                summary = summarize(state.format, state.quality),
                destinationLabel = if (destination is Destination.MusicLibrary) {
                    "Music/TransMusic"
                } else {
                    treeName ?: "自选文件夹"
                },
                workers = state.workers,
                enabled = !running,
                onEdit = { showSettings = true },
            )

            Box(Modifier.weight(1f)) {
                if (jobs.isEmpty()) {
                    EmptyHint(Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 6.dp),
                    ) {
                        items(jobs, key = { it.id }) { job ->
                            JobCard(job = job, onClick = { detail = job })
                        }
                    }
                }
            }

            ProgressFooter(jobs = jobs, statusLine = statusLine)

            AnimatedVisibility(
                visible = showLogs,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                LogPanel(logs = logs)
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            SettingsSheet(
                state = state,
                useMusicLibrary = destination is Destination.MusicLibrary,
                destinationLabel = if (destination is Destination.MusicLibrary) {
                    "Music/TransMusic（系统媒体库）"
                } else {
                    treeName ?: "自选文件夹"
                },
                onDestinationChange = { useLibrary ->
                    if (useLibrary) {
                        treeUri = null
                        treeName = null
                    } else {
                        pickTree.launch(null)
                    }
                },
            )
        }
    }

    val current = detail
    if (current != null) {
        JobDetailDialog(
            job = current,
            onDismiss = { detail = null },
            onRemove = {
                viewModel.remove(current.id)
                detail = null
            },
        )
    }
}

// --------------------------------------------------------------------------- //
// 局部组件
// --------------------------------------------------------------------------- //

@Composable
private fun ScreenHeader(
    engineVersion: String,
    showLogs: Boolean,
    onAdd: () -> Unit,
    onToggleLogs: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 10.dp, top = 6.dp, bottom = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "音频格式转换器",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.onBackground,
                )
                Text(
                    text = "ffmpeg $engineVersion · 15 种格式互转",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onToggleLogs) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = if (showLogs) "隐藏日志" else "查看日志",
                    tint = if (showLogs) colors.primary else colors.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "清空列表",
                    tint = colors.onSurfaceVariant,
                )
            }
            FilledTonalIconButton(onClick = onAdd, shape = CircleShape) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "添加音频")
            }
        }
    }
}

@Composable
private fun OutputSummaryCard(
    summary: String,
    destinationLabel: String,
    workers: Int,
    enabled: Boolean,
    onEdit: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "输出",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onPrimaryContainer.copy(alpha = 0.7f),
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "$destinationLabel · 并发 $workers",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onPrimaryContainer.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            FilledTonalButton(onClick = onEdit, enabled = enabled, shape = MaterialTheme.shapes.medium) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("调整", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ProgressFooter(jobs: List<Job>, statusLine: String) {
    val colors = MaterialTheme.colorScheme
    val overall = remember(jobs) {
        if (jobs.isEmpty()) {
            0f
        } else {
            val sum = jobs.sumOf { job ->
                when (job.status) {
                    JobStatus.DONE -> 100.0
                    JobStatus.RUNNING -> job.progress.toDouble()
                    else -> 0.0
                }
            }
            (sum / jobs.size / 100.0).toFloat()
        }
    }
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { overall },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape),
            color = colors.primary,
            trackColor = colors.surfaceContainerHighest,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = statusLine,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (jobs.isNotEmpty()) {
                val done = jobs.count { it.status == JobStatus.DONE }
                Text(
                    text = "$done/${jobs.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ActionBar(
    running: Boolean,
    canStart: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpringButton(
                text = if (running) "转换中…" else "开始转换",
                enabled = !running && canStart,
                onClick = onStart,
                modifier = Modifier.weight(1f),
            )
            if (running) {
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.height(54.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Text("取消")
                }
            }
        }
    }
}

/** MD3E 的「按下去会弹一下」的主按钮。 */
@Composable
private fun SpringButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.955f else 1f,
        animationSpec = Motion.snappy,
        label = "pressScale",
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(54.dp)
            .scale(scale),
        shape = MaterialTheme.shapes.large,
        interactionSource = interaction,
        contentPadding = PaddingValues(horizontal = 24.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun LogPanel(logs: List<String>) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 132.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            if (logs.isEmpty()) {
                Text(
                    text = "暂无日志",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                logs.takeLast(40).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun JobDetailDialog(job: Job, onDismiss: () -> Unit, onRemove: () -> Unit) {
    val info = job.info
    val body = buildString {
        appendLine("编码：${info.codec.ifBlank { "-" }}")
        appendLine("时长：${info.durationText}　码率：${info.bitrateText}")
        appendLine("采样率：${info.sampleRateText}　声道：${info.channelsText}")
        appendLine("内嵌封面：${if (info.hasCover) "有" else "无"}")
        if (info.title.isNotBlank()) appendLine("标题：${info.title}")
        if (info.artist.isNotBlank()) appendLine("艺人：${info.artist}")
        if (info.album.isNotBlank()) appendLine("专辑：${info.album}")
        if (job.message.isNotBlank()) appendLine("结果：${job.message}")
        if (job.elapsedMs > 0) {
            appendLine("耗时：${String.format(Locale.US, "%.1f", job.elapsedMs / 1000.0)} 秒")
        }
        append("状态：${job.status.text}")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = job.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = { Text(text = body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = { TextButton(onClick = onRemove) { Text("移除") } },
    )
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
