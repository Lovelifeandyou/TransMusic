package com.transmusic.converter.model

/**
 * 探测策略：系统的 MediaMetadataRetriever / MediaExtractor 不认识的格式（DSD、APE、部分 WMA 等）
 * 并不代表 ffmpeg 不能解。所以「系统能解析」或者「扩展名在支持列表里」都应当放行，
 * 否则这类文件会被队列直接过滤掉、根本不会尝试转换。
 */
object ProbePolicy {
    fun shouldAccept(systemParsed: Boolean, displayName: String?): Boolean {
        if (systemParsed) return true
        val name = displayName ?: return false
        return AudioExts.isAudioLike(name)
    }
}
