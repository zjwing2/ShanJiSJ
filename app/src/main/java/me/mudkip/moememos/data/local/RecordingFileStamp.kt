package me.mudkip.moememos.data.local

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 录音文件名的「时刻」编解码。
 *
 * 录音文件名形如 `voice-20261006-221532.m4a`。这个时间戳不只是给人看的：
 * 转写文字追加进正文时，那个 `[语音 22:15]` 标记就是从这里还原出来的
 * （见 [me.mudkip.moememos.data.transcription.TranscriptionManager]）。
 *
 * 之所以单独抽出来，是因为它是**跨模块的隐式契约**：写的一头在
 * [VoiceRecorder]，读的一头在转写侧，两边一旦漂移不会报错，只会安静地
 * 退化成「时间标记不准」，很难在真机上发现。抽成一个可单测的对象，
 * 契约就有了守卫。
 */
object RecordingFileStamp {

    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** 用于在整串文件名里找出时间戳，而不是要求文件名严格等于它。 */
    private val PATTERN = Regex("""\d{8}-\d{6}""")

    /** 把时刻编码成文件名片段。 */
    fun format(time: LocalDateTime): String = FORMAT.format(time)

    /** 组装完整的录音文件名。 */
    fun fileNameFor(time: LocalDateTime): String = "voice-${format(time)}.m4a"

    /**
     * 从文件名里还原录音时刻。
     *
     * 认不出来返回 null，而不是抛异常或猜一个近似值：调用方会退回「当前时刻」，
     * 那是可接受的降级；猜错则会在正文里留下一个明确写错的时间，更糟。
     */
    fun parse(fileName: String): LocalDateTime? {
        val match = PATTERN.find(fileName) ?: return null
        return runCatching { LocalDateTime.parse(match.value, FORMAT) }.getOrNull()
    }
}
