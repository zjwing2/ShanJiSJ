package me.mudkip.moememos.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

/**
 * 录音文件名时间戳的读写契约。
 *
 * 这组用例守的是一条**跨模块的隐式约定**：写的一头是 [VoiceRecorder]，
 * 读的一头是转写侧决定「正文里那个时间标记写几点」的地方。两边格式一旦漂移，
 * 不会有任何报错，只会安静地退化成「时间标记不准」——所以必须用测试钉住。
 */
class RecordingFileStampTest {

    private val moment = LocalDateTime.of(2026, 10, 6, 22, 15, 32)

    @Test
    fun format_usesSortableCompactForm() {
        assertEquals("20261006-221532", RecordingFileStamp.format(moment))
    }

    @Test
    fun fileNameFor_matchesVoiceRecorderConvention() {
        assertEquals("voice-20261006-221532.m4a", RecordingFileStamp.fileNameFor(moment))
    }

    @Test
    fun formatThenParse_roundTrips() {
        val names = listOf(
            LocalDateTime.of(2026, 1, 1, 0, 0, 0),
            LocalDateTime.of(2026, 10, 6, 22, 15, 32),
            LocalDateTime.of(2026, 12, 31, 23, 59, 59),
        )
        for (time in names) {
            assertEquals(time, RecordingFileStamp.parse(RecordingFileStamp.fileNameFor(time)))
        }
    }

    @Test
    fun parse_readsTimestampOutOfARealFileName() {
        assertEquals(moment, RecordingFileStamp.parse("voice-20261006-221532.m4a"))
    }

    @Test
    fun parse_toleratesSuffixesAndPrefixes() {
        // 用户或同步工具改名后仍要能认出时间（例如 OneDrive 冲突副本）
        assertEquals(moment, RecordingFileStamp.parse("voice-20261006-221532 (1).m4a"))
        assertEquals(moment, RecordingFileStamp.parse("1020_voice-20261006-221532.m4a"))
    }

    @Test
    fun parse_returnsNullWhenThereIsNoTimestamp() {
        assertNull(RecordingFileStamp.parse("voice.m4a"))
        assertNull(RecordingFileStamp.parse("会议记录.m4a"))
        assertNull(RecordingFileStamp.parse(""))
    }

    @Test
    fun parse_returnsNullWhenTheTimestampIsIncomplete() {
        // 少了秒：宁可退回「当前时刻」，也不要猜一个可能错的时间写进正文
        assertNull(RecordingFileStamp.parse("voice-20261006-2215.m4a"))
        assertNull(RecordingFileStamp.parse("voice-20261006.m4a"))
    }

    @Test
    fun parse_returnsNullOnImpossibleDates() {
        assertNull(RecordingFileStamp.parse("voice-20261340-221532.m4a"))
        assertNull(RecordingFileStamp.parse("voice-20261006-256199.m4a"))
    }

    @Test
    fun parse_handlesLeapDay() {
        val leap = LocalDateTime.of(2028, 2, 29, 8, 5, 0)
        assertEquals(leap, RecordingFileStamp.parse(RecordingFileStamp.fileNameFor(leap)))
    }
}
