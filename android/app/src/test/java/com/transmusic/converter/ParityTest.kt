package com.transmusic.converter

import com.transmusic.converter.core.MediaInfo
import com.transmusic.converter.engine.CommandBuilder
import com.transmusic.converter.engine.ConvertSettings
import com.transmusic.converter.model.Formats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三端 ffmpeg 参数一致性（Android 侧）。
 *
 * 期望值由 Python 引擎（transmusic/engine.py，唯一数据源）生成，
 * 资源文件用 `python tools/gen_android_parity_cases.py` 重建。
 * 与 Windows 端的 parity_winui_test.py 跑的是同一批用例。
 */
class ParityTest {

    private val sep = '\u0001'

    @Test
    fun commandBuilderMatchesPythonForAllCases() {
        val stream = javaClass.classLoader?.getResourceAsStream("parity_cases.txt")
            ?: error("缺少 parity_cases.txt：先运行 python tools/gen_android_parity_cases.py")

        val lines = stream.bufferedReader(Charsets.UTF_8).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }

        val srcPath = "D:/音乐/in 1.mp3"
        val dstPath = "D:/out/out 1.flac"

        var checked = 0
        val failures = mutableListOf<String>()
        var i = 0
        while (i + 1 < lines.size) {
            val fields = lines[i].split(sep)
            val expected = lines[i + 1].split(sep)
            i += 2

            val id = fields[0]
            val fmtKey = fields[1]
            val settings = ConvertSettings(
                formatKey = fmtKey,
                bitrate = fields[2].toInt(),
                vbrIndex = fields[3].toInt(),
                sampleRate = fields[4].toInt(),
                channels = fields[5].toInt(),
                bitDepth = fields[6].toInt(),
                compLevel = fields[7].toInt(),
                keepMetadata = fields[8] == "1",
                keepCover = fields[9] == "1",
            )
            val info = MediaInfo(
                sampleRate = fields[10].toInt(),
                channels = fields[11].toInt(),
                hasCover = fields[12] == "1",
                codec = fields[13],
            )

            val actual = CommandBuilder.build(srcPath, dstPath, Formats.get(fmtKey), settings, info)
            checked++
            if (actual != expected) {
                failures += diffOf(id, expected, actual)
            }
        }

        assertTrue("没有读到任何用例", checked > 0)
        assertEquals(
            "Android 与 Python 参数不一致：\n" + failures.take(5).joinToString("\n"),
            emptyList<String>(),
            failures,
        )
        println("Android 端一致性：$checked 个用例全部与 Python 相同")
    }

    private fun diffOf(id: String, expected: List<String>, actual: List<String>): String {
        val sb = StringBuilder("$id:")
        for (index in 0 until maxOf(expected.size, actual.size)) {
            val e = expected.getOrNull(index) ?: "<缺失>"
            val a = actual.getOrNull(index) ?: "<缺失>"
            if (e != a) sb.append("\n    位置 $index: python=$e android=$a")
        }
        return sb.toString()
    }
}
