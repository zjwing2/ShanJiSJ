package me.mudkip.moememos.data.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 转写文字追加进正文的格式。
 *
 * 用户对这个功能的全部感知就是这一段文字长什么样，所以格式被钉死在这里：
 * 开头空一行、时间标记独占一行、末尾不留空白。
 */
class TranscriptionBlockTest {

    private val moment = LocalDateTime.of(2026, 10, 6, 22, 15, 32)

    @Test
    fun buildsTheExactShape() {
        val block = buildTranscriptionBlock("语音", moment, "明天下午三点跟张工对一下接口文档。")
        assertEquals("\n\n[语音 22:15]\n明天下午三点跟张工对一下接口文档。", block)
    }

    @Test
    fun labelFollowsTheLanguage() {
        val block = buildTranscriptionBlock("Voice", moment, "Ship it.")
        assertTrue(block.contains("[Voice 22:15]"))
    }

    @Test
    fun clockIsZeroPadded() {
        val early = LocalDateTime.of(2026, 10, 6, 7, 5, 0)
        assertEquals("\n\n[语音 07:05]\n早", buildTranscriptionBlock("语音", early, "早"))
    }

    @Test
    fun midnightDoesNotBecome24() {
        val midnight = LocalDateTime.of(2026, 10, 6, 0, 0, 0)
        assertEquals("\n\n[语音 00:00]\n夜", buildTranscriptionBlock("语音", midnight, "夜"))
    }

    @Test
    fun trimsSurroundingWhitespaceOfTheText() {
        // 识别结果常带首尾空白；留着会变成正文里的悬挂空行
        val block = buildTranscriptionBlock("语音", moment, "\n  你好世界  \n")
        assertEquals("\n\n[语音 22:15]\n你好世界", block)
    }

    @Test
    fun appendingTwiceKeepsBothBlocksSeparated() {
        val first = buildTranscriptionBlock("语音", moment, "第一段")
        val second = buildTranscriptionBlock("语音", LocalDateTime.of(2026, 10, 6, 23, 40, 0), "第二段")
        val body = "原本的正文" + first + second
        assertEquals(
            "原本的正文\n\n[语音 22:15]\n第一段\n\n[语音 23:40]\n第二段",
            body,
        )
    }
}
