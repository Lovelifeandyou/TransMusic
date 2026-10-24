package com.transmusic.converter.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.transmusic.converter.core.Job
import com.transmusic.converter.core.JobStatus
import com.transmusic.converter.ui.theme.AccentError
import com.transmusic.converter.ui.theme.AccentOk
import com.transmusic.converter.ui.theme.AccentWarn

/** 单条转换任务的卡片：文件名 / 编码信息 / 状态 / 进度。 */
@Composable
fun JobCard(job: Job, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = statusColor(job.status, colors.primary)
    val showProgress = job.status == JobStatus.RUNNING || job.status == JobStatus.DONE
    val progress = when (job.status) {
        JobStatus.DONE -> 1f
        JobStatus.RUNNING -> (job.progress / 100f).coerceIn(0f, 1f)
        else -> 0f
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = job.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                if (job.status == JobStatus.RUNNING) {
                    PulsingDot(color = accent)
                    Spacer(Modifier.width(6.dp))
                }
                StatusPill(text = statusText(job), color = accent)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = job.metaText,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showProgress) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(CircleShape),
                        color = accent,
                        trackColor = colors.surfaceContainerHighest,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                    )
                }
            }
            if (job.status == JobStatus.DONE && job.message.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "→ ${job.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun statusText(job: Job): String = when (job.status) {
    JobStatus.WAITING -> "等待"
    JobStatus.RUNNING -> "转换中"
    JobStatus.DONE -> "完成"
    JobStatus.FAILED -> "失败"
    JobStatus.SKIPPED -> "跳过"
    JobStatus.CANCELED -> "已取消"
}

private fun statusColor(status: JobStatus, primary: Color): Color = when (status) {
    JobStatus.DONE -> AccentOk
    JobStatus.FAILED -> AccentError
    JobStatus.SKIPPED -> AccentWarn
    JobStatus.RUNNING -> primary
    else -> AccentWarn
}

/** 空列表时的引导（大图标 + 两行说明）。 */
@Composable
fun EmptyHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "还没有文件",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "点右上角「＋」选择音频或视频\nMP3 · FLAC · WAV · AAC · ALAC · OGG · Opus · WMA · AIFF · WV · AC3",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
        )
    }
}
