package com.transmusic.converter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.transmusic.converter.model.Formats
import com.transmusic.converter.model.Preset
import com.transmusic.converter.model.Presets
import kotlin.math.roundToInt

/**
 * 设置面板（MD3E 的 Modal Bottom Sheet）。
 * 所有控件都直接改写 [ConvertSettingsState]，主界面上的摘要实时联动。
 */
@Composable
fun SettingsSheet(
    state: ConvertSettingsState,
    useMusicLibrary: Boolean,
    destinationLabel: String,
    onDestinationChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 640.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(bottom = 28.dp),
    ) {
        Text("转换设置", style = MaterialTheme.typography.headlineSmall, color = colors.onSurface)
        Spacer(Modifier.height(2.dp))
        Text(
            text = "参数与 Windows 桌面版完全一致 · 常用预设一键套用",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        SectionCard(title = "常用预设") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Presets.ALL.forEach { preset ->
                    AssistChip(
                        onClick = { state.applyPreset(preset) },
                        shape = MaterialTheme.shapes.small,
                        label = {
                            Text(preset.label, style = MaterialTheme.typography.labelMedium)
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard(title = "目标格式", subtitle = state.format.note) {
            ChoiceChips(
                options = Formats.ORDERED,
                selected = state.format,
                label = { it.display },
            ) { selected -> state.selectFormat(selected.key) }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard(title = "质量") {
            val items = state.qualityItems
            ChoiceChips(
                options = items,
                selected = state.quality ?: items.first(),
                label = { it.label },
            ) { item -> state.selectQuality(items.indexOf(item)) }
        }

        val compRange = state.compRange
        if (compRange != null) {
            Spacer(Modifier.height(12.dp))
            SectionCard(title = "压缩级别", subtitle = "越大体积越小、编码越慢") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = state.compLevel.coerceIn(compRange.first, compRange.last).toFloat(),
                        onValueChange = { state.compLevel = it.roundToInt() },
                        valueRange = compRange.first.toFloat()..compRange.last.toFloat(),
                        steps = (compRange.last - compRange.first - 1).coerceAtLeast(0),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${state.compLevel.coerceIn(compRange.first, compRange.last)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard(title = "采样率") {
            ChoiceChips(
                options = state.sampleRates,
                selected = state.sampleRate,
                label = { sampleRateLabel(it) },
            ) { state.sampleRate = it }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard(title = "声道") {
            ChoiceChips(
                options = state.channelList,
                selected = state.channels,
                label = { channelLabel(it) },
            ) { state.channels = it }
        }

        Spacer(Modifier.height(12.dp))
        SectionCard(title = "保存到", subtitle = destinationLabel) {
            ChoiceChips(
                options = listOf(true, false),
                selected = useMusicLibrary,
                label = { if (it) "系统音乐库" else "自选文件夹" },
                onSelect = onDestinationChange,
            )
        }

        Spacer(Modifier.height(12.dp))
        SectionCard(title = "元数据") {
            ToggleRow(
                label = "保留标签（ID3 / Vorbis / MP4）",
                checked = state.keepMetadata,
                onChange = { state.keepMetadata = it },
            )
            ToggleRow(
                label = "复制内嵌封面",
                checked = state.keepCover,
                onChange = { state.keepCover = it },
            )
        }

        Spacer(Modifier.height(12.dp))
        SectionCard(title = "并发任务数", subtitle = "同时转换 ${state.workers} 个文件") {
            Slider(
                value = state.workers.toFloat(),
                onValueChange = { state.workers = it.roundToInt().coerceIn(1, 8) },
                valueRange = 1f..8f,
                steps = 6,
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 套用预设：格式 + 质量 + 采样率 + 声道 + 压缩级别一起改。 */
private fun ConvertSettingsState.applyPreset(preset: Preset) {
    val fmt = Formats.find(preset.formatKey) ?: return
    selectFormat(fmt.key)
    val items = buildQuality(fmt)
    val index = when {
        preset.vbrIndex >= 0 -> items.indexOfFirst { it.vbrIndex == preset.vbrIndex }
        preset.bitrate > 0 -> items.indexOfFirst { it.bitrate == preset.bitrate }
        preset.bitDepth > 0 -> items.indexOfFirst { it.bitDepth == preset.bitDepth }
        else -> -1
    }
    if (index >= 0) qualityIndex = index
    sampleRate = if (preset.sampleRate > 0 && sampleRateOptions(fmt).contains(preset.sampleRate)) {
        preset.sampleRate
    } else {
        0
    }
    channels = when {
        fmt.maxChannels <= 1 -> 1
        preset.channels > 0 && channelOptions(fmt).contains(preset.channels) -> preset.channels
        else -> 0
    }
    if (preset.compLevel >= 0 && fmt.compLevels != null) compLevel = preset.compLevel
}
