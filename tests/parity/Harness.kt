package harness

import com.transmusic.converter.core.MediaInfo
import com.transmusic.converter.engine.CommandBuilder
import com.transmusic.converter.engine.ConvertSettings
import com.transmusic.converter.model.Formats

/**
 * 一致性测试用的命令行客户端：
 * 从 stdin 读用例，用 Android 端真实的 CommandBuilder 生成 ffmpeg 参数并打到 stdout。
 * Python 侧（tests/parity_test.py）用 Windows 端的 build_command 生成同一批参数并 diff。
 *
 * 协议：
 *   CASE <id>
 *   fmt=mp3 / bitrate=320 / vbr=-1 / sr=0 / ch=0 / depth=0 / comp=-1
 *   meta=1 / cover=1 / srcSr=44100 / srcCh=2 / srcCover=0
 *   srcPath=D:/音乐/in 1.mp3 / dstPath=D:/out/out 1.flac
 *   END
 */

private class Case(val id: String) {
    var fmt: String = ""
    var bitrate: Int = 0
    var vbr: Int = -1
    var sr: Int = 0
    var ch: Int = 0
    var depth: Int = 0
    var comp: Int = -1
    var meta: Boolean = true
    var cover: Boolean = true
    var srcSr: Int = 0
    var srcCh: Int = 0
    var srcCover: Boolean = false
    var srcCodec: String = ""
    var srcPath: String = ""
    var dstPath: String = ""
}

fun main() {
    val cases = mutableListOf<Case>()
    var current: Case? = null

    while (true) {
        val line = readLine() ?: break
        val text = line.trim()
        if (text.isEmpty()) continue
        when {
            text.startsWith("CASE ") -> {
                val case = Case(text.removePrefix("CASE ").trim())
                cases += case
                current = case
            }
            text == "END" -> current = null
            current != null -> {
                val sep = text.indexOf('=')
                if (sep <= 0) continue
                val key = text.substring(0, sep).trim()
                val value = text.substring(sep + 1).trim()
                val case = current ?: continue
                when (key) {
                    "fmt" -> case.fmt = value
                    "bitrate" -> case.bitrate = value.toIntOrNull() ?: 0
                    "vbr" -> case.vbr = value.toIntOrNull() ?: -1
                    "sr" -> case.sr = value.toIntOrNull() ?: 0
                    "ch" -> case.ch = value.toIntOrNull() ?: 0
                    "depth" -> case.depth = value.toIntOrNull() ?: 0
                    "comp" -> case.comp = value.toIntOrNull() ?: -1
                    "meta" -> case.meta = value == "1"
                    "cover" -> case.cover = value == "1"
                    "srcSr" -> case.srcSr = value.toIntOrNull() ?: 0
                    "srcCh" -> case.srcCh = value.toIntOrNull() ?: 0
                    "srcCover" -> case.srcCover = value == "1"
                    "srcCodec" -> case.srcCodec = value
                    "srcPath" -> case.srcPath = value
                    "dstPath" -> case.dstPath = value
                }
            }
        }
    }

    for (case in cases) {
        println("CASE ${case.id}")
        val format = Formats.find(case.fmt)
        if (format == null) {
            println("ERROR unknown-format ${case.fmt}")
            println("END")
            continue
        }
        val settings = ConvertSettings(
            formatKey = format.key,
            bitrate = case.bitrate,
            vbrIndex = case.vbr,
            sampleRate = case.sr,
            channels = case.ch,
            bitDepth = case.depth,
            compLevel = case.comp,
            keepMetadata = case.meta,
            keepCover = case.cover,
        )
        val info = MediaInfo(
            sampleRate = case.srcSr,
            channels = case.srcCh,
            hasCover = case.srcCover,
            codec = case.srcCodec,
        )
        val args = CommandBuilder.build(case.srcPath, case.dstPath, format, settings, info)
        for (arg in args) println("ARG $arg")
        println("END")
    }
    System.out.flush()
}
