package com.transmusic.converter.core

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.transmusic.converter.engine.ConvertSettings
import com.transmusic.converter.model.AudioFormat
import com.transmusic.converter.util.Destination

/** 上次使用的参数（对应 Windows 版 settings.json）。 */
data class SavedSettings(
    val formatKey: String = "mp3",
    val qualityIndex: Int = 0,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    val compLevel: Int = -1,
    val keepMeta: Boolean = true,
    val keepCover: Boolean = true,
    val workers: Int = 2,
    val template: String = "{name}",
)

/**
 * 界面状态容器：把 [ConvertQueue] 的 StateFlow 直接暴露给 Compose，
 * 顺带负责参数的持久化（SharedPreferences）。
 */
class ConvertViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val queue = ConvertQueue(app, viewModelScope)

    val jobs = queue.jobs
    val running = queue.running
    val status = queue.status
    val logs = queue.logs

    var savedSettings: SavedSettings = SavedSettings(
        formatKey = prefs.formatKey,
        qualityIndex = prefs.qualityIndex,
        sampleRate = prefs.sampleRate,
        channels = prefs.channels,
        compLevel = prefs.compLevel,
        keepMeta = prefs.keepMeta,
        keepCover = prefs.keepCover,
        workers = prefs.workers,
        template = prefs.template,
    )
        private set

    val isRunning: Boolean get() = queue.isRunning

    fun addUris(uris: List<Uri>) = queue.addUris(uris)

    fun remove(id: Long) = queue.remove(id)

    fun clearAll() = queue.clearAll()

    fun cancel() = queue.cancel()

    fun start(
        format: AudioFormat,
        settings: ConvertSettings,
        workers: Int,
        qualityIndex: Int,
        destination: Destination,
    ) {
        persist(format, settings, workers, qualityIndex)
        queue.start(
            fmt = format,
            settings = settings,
            workers = workers,
            destination = destination,
            template = savedSettings.template,
        )
    }

    private fun persist(
        format: AudioFormat,
        settings: ConvertSettings,
        workers: Int,
        qualityIndex: Int,
    ) {
        prefs.formatKey = format.key
        prefs.qualityIndex = qualityIndex
        prefs.sampleRate = settings.sampleRate
        prefs.channels = settings.channels
        prefs.compLevel = settings.compLevel
        prefs.keepMeta = settings.keepMetadata
        prefs.keepCover = settings.keepCover
        prefs.workers = workers
        savedSettings = savedSettings.copy(
            formatKey = format.key,
            qualityIndex = qualityIndex,
            sampleRate = settings.sampleRate,
            channels = settings.channels,
            compLevel = settings.compLevel,
            keepMeta = settings.keepMetadata,
            keepCover = settings.keepCover,
            workers = workers,
        )
    }
}
