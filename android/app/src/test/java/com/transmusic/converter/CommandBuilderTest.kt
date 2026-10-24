package com.transmusic.converter

import com.transmusic.converter.core.MediaInfo
import com.transmusic.converter.engine.CommandBuilder
import com.transmusic.converter.engine.ConvertSettings
import com.transmusic.converter.engine.DsdSupport
import com.transmusic.converter.model.Formats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎层的纯逻辑单测（不需要真机/模拟器）：
 *  - DSD 采样率换算与降采样/低通参数
 *  - 采样率吸附、位深映射、编码器选择
 *  - 命令参数与 Windows / Python 端保持一致的关键约定
 */
class CommandBuilderTest {

    private fun build(
        fmtKey: String,
        settings: ConvertSettings = ConvertSettings(),
        info: MediaInfo = MediaInfo(),
        srcPath: String = "in.mp3",
    ): List<String> = CommandBuilder.build(srcPath, "out.tmp", Formats.get(fmtKey), settings, info)

    // ---------------- DSD ----------------

    @Test
    fun dsdRatesMapToPcmTargets() {
        assertEquals(88200, DsdSupport.pcmTarget(352800))    // DSD64（ffmpeg 解码后）
        assertEquals(88200, DsdSupport.pcmTarget(2822400))   // DSD64（原生率）
        assertEquals(176400, DsdSupport.pcmTarget(705600))   // DSD128
        assertEquals(176400, DsdSupport.pcmTarget(5644800))  // DSD128（原生率）
        assertEquals(352800, DsdSupport.pcmTarget(1411200))  // DSD256
        assertEquals(352800, DsdSupport.pcmTarget(11289600)) // DSD256（原生率）
    }

    @Test
    fun dsdSourceDetection() {
        assertTrue(DsdSupport.isDsdSource("dsd_lsbf_planar", 352800))
        assertTrue(DsdSupport.isDsdSource("", 2822400))
        assertTrue(DsdSupport.isDsdSource("", 0, "/music/a.dsf"))
        assertTrue(DsdSupport.isDsdSource("", 0, "D:/music/album/TRACK.DFF"))
        assertFalse(DsdSupport.isDsdSource("flac", 44100, "/music/a.flac"))
        assertFalse(DsdSupport.isDsdSource("", 0))
    }

    @Test
    fun dsdSourceGetsLowpassAndDownsample() {
        val info = MediaInfo(sampleRate = 2822400, channels = 2, codec = "dsd_lsbf_planar")
        val args = build("flac", ConvertSettings(bitDepth = 24, compLevel = 8), info, "a.dsf")
        assertTrue("DSD 降到 88.2k", args.contains("88200"))
        assertTrue("低通抑制混叠", args.containsAll(listOf("-af", "lowpass=f=30000")))
        assertTrue("24bit → s32", args.containsAll(listOf("-sample_fmt", "s32")))
    }

    @Test
    fun dsdToMp3Uses48k() {
        val info = MediaInfo(sampleRate = 352800, channels = 2, codec = "dsd_lsbf_planar")
        val args = build("mp3", ConvertSettings(bitrate = 320), info, "a.dsf")
        assertTrue(args.containsAll(listOf("-ar", "48000")))
        assertTrue(args.containsAll(listOf("-af", "lowpass=f=30000")))
    }

    @Test
    fun highRateDsdKeepsPcmRateWithoutLowpass() {
        // DSD256 → 352.8k 输出：高于 96k，不再加低通
        val info = MediaInfo(sampleRate = 11289600, channels = 2, codec = "dsd_msbf_planar")
        val args = build("wav", ConvertSettings(bitDepth = 24), info, "a.dff")
        assertTrue(args.containsAll(listOf("-ar", "352800")))
        assertFalse(args.contains("-af"))
    }

    @Test
    fun nonDsdSourceKeepsOriginalRate() {
        val args = build("flac", ConvertSettings(bitDepth = 16), MediaInfo(sampleRate = 44100, channels = 2, codec = "flac"))
        assertFalse("-ar" in args)
        assertFalse("-af" in args)
    }

    // ---------------- 常规参数 ----------------

    @Test
    fun mp3CommandShape() {
        val args = build("mp3", ConvertSettings(bitrate = 320), MediaInfo(sampleRate = 44100, channels = 2))
        assertEquals(
            listOf(
                "-hide_banner", "-nostdin", "-y", "-loglevel", "error", "-i", "in.mp3",
                "-map", "0:a:0", "-vn", "-sn", "-dn",
                "-c:a", "libmp3lame", "-b:a", "320k",
                "-map_metadata", "0", "-map_chapters", "-1",
                "-id3v2_version", "3", "-write_id3v1", "1",
                "out.tmp",
            ),
            args,
        )
    }

    @Test
    fun losslessBitDepthMapping() {
        assertTrue(build("wav", ConvertSettings(bitDepth = 24)).contains("pcm_s24le"))
        assertTrue(build("wav", ConvertSettings(bitDepth = 32)).contains("pcm_f32le"))
        assertTrue(build("aiff", ConvertSettings(bitDepth = 16)).contains("pcm_s16be"))
        assertTrue(build("flac", ConvertSettings(bitDepth = 24)).containsAll(listOf("-sample_fmt", "s32")))
        assertTrue(build("m4a_alac", ConvertSettings(bitDepth = 24)).containsAll(listOf("-sample_fmt", "s32p")))
    }

    @Test
    fun channelAndRateClamping() {
        // opus 只支持 48k
        assertTrue(build("opus", ConvertSettings(bitrate = 128), MediaInfo(sampleRate = 44100)).containsAll(listOf("-ar", "48000")))
        // speex 强制单声道
        assertTrue(build("spx", ConvertSettings(bitrate = 16), MediaInfo(channels = 2)).containsAll(listOf("-ac", "1")))
        // 96k 源转 mp3：吸附到 48k
        assertTrue(build("mp3", ConvertSettings(), MediaInfo(sampleRate = 96000)).containsAll(listOf("-ar", "48000")))
    }

    @Test
    fun probePolicyAcceptsFormatsSystemCannotParse() {
        // 系统解不了但 ffmpeg 能解：DSD / APE
        assertTrue(com.transmusic.converter.model.ProbePolicy.shouldAccept(false, "album.dsf"))
        assertTrue(com.transmusic.converter.model.ProbePolicy.shouldAccept(false, "TRACK.DFF"))
        assertTrue(com.transmusic.converter.model.ProbePolicy.shouldAccept(false, "song.ape"))
        assertTrue(com.transmusic.converter.model.ProbePolicy.shouldAccept(false, "old.wma"))
        // 系统能解析：任何格式都放行
        assertTrue(com.transmusic.converter.model.ProbePolicy.shouldAccept(true, "a.mp3"))
        // 完全无关的扩展名且系统解不了：拒绝
        assertFalse(com.transmusic.converter.model.ProbePolicy.shouldAccept(false, "notes.txt"))
        assertFalse(com.transmusic.converter.model.ProbePolicy.shouldAccept(false, null))
    }

    @Test
    fun formatsTableIsComplete() {
        assertEquals(15, Formats.ORDERED.size)
        assertEquals(11, com.transmusic.converter.model.Presets.ALL.size)
        assertEquals("libmp3lame", Formats.get("mp3").codec)
        assertTrue(Formats.get("flac").lossless)
        assertFalse(Formats.get("opus").lossless)
    }
}
