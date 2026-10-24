package com.transmusic.converter.engine

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import com.transmusic.converter.core.MediaInfo

/** 读取时长 / 码率 / 采样率 / 声道 / 编码 / 封面信息（不复制文件）。 */
object AudioProbe {

    private val codecLabels = mapOf(
        "audio/mpeg" to "MP3",
        "audio/mp4a-latm" to "AAC",
        "audio/aac" to "AAC",
        "audio/flac" to "FLAC",
        "audio/x-flac" to "FLAC",
        "audio/alac" to "ALAC",
        "audio/opus" to "OPUS",
        "audio/x-opus" to "OPUS",
        "audio/vorbis" to "VORBIS",
        "audio/x-vorbis" to "VORBIS",
        "audio/x-wav" to "PCM",
        "audio/wav" to "PCM",
        "audio/raw" to "PCM",
        "audio/x-ms-wma" to "WMA",
        "audio/ac3" to "AC3",
        "audio/eac3" to "EAC3",
        "audio/amr-wb" to "AMR-WB",
        "audio/3gpp" to "AMR",
        "audio/3gpp2" to "AAC",
        "audio/spx" to "SPEEX",
        "audio/x-ape" to "APE",
        "audio/x-wavpack" to "WAVPACK",
        "audio/x-tta" to "TTA",
        "audio/mp2" to "MP2",
    )

    fun probe(context: Context, uri: Uri, displayName: String): MediaInfo {
        var durationMs = 0L
        var bitrate = 0
        var hasCover = false
        var title = ""
        var artist = ""
        var album = ""
        var error = ""

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
            hasCover = retriever.embeddedPicture != null
        } catch (e: Exception) {
            error = e.message ?: "无法读取音频"
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        var sampleRate = 0
        var channels = 0
        var codec = ""
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                codec = codecLabels[mime] ?: mime.removePrefix("audio/").uppercase()
                break
            }
        } catch (_: Exception) {
            // 系统解析不了（如 APE/WMA 老文件），交给 ffmpeg 自己处理
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }

        if (codec.isBlank()) {
            codec = displayName.substringAfterLast('.', "").uppercase()
        }
        if (bitrate <= 0 && durationMs > 0) {
            val size = querySize(context, uri)
            if (size > 0) bitrate = (size * 8 / (durationMs / 1000.0)).toInt()
        }

        val ok = error.isEmpty() && (durationMs > 0 || sampleRate > 0)
        return MediaInfo(
            durationMs = durationMs,
            bitrate = bitrate,
            sampleRate = sampleRate,
            channels = channels,
            codec = codec,
            hasCover = hasCover,
            title = title,
            artist = artist,
            album = album,
            ok = ok,
            error = if (ok) "" else error.ifBlank { "无法识别的音频" },
        )
    }

    private fun querySize(context: Context, uri: Uri): Long {
        val projection = arrayOf(MediaStore.MediaColumns.SIZE)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    /** 目标格式 → MIME，用于 MediaStore / SAF 写入。 */
    fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "wav" -> "audio/x-wav"
        "aiff", "aif" -> "audio/x-aiff"
        "ogg", "spx" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wma" -> "audio/x-ms-wma"
        "ac3" -> "audio/ac3"
        "eac3" -> "audio/eac3"
        "mp2" -> "audio/mpeg"
        "wv" -> "audio/x-wavpack"
        "tta" -> "audio/x-tta"
        else -> "audio/*"
    }
}
