package me.mudkip.moememos.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 从自由文本里挑链接。
 *
 * 中文写作里链接后面跟着标点是常态，标点被吞进地址里会让抓取直接失败，
 * 所以这些用例基本都是围着「结尾标点」转的。
 */
class WebUrlTest {

    @Test
    fun `finds a plain url`() {
        assertEquals(
            "https://example.com/post",
            findFirstWebUrl("看这个 https://example.com/post 挺有意思")
        )
    }

    @Test
    fun `stops before a chinese full stop`() {
        assertEquals(
            "https://example.com/post",
            findFirstWebUrl("详见 https://example.com/post。")
        )
    }

    @Test
    fun `stops before a trailing ascii period`() {
        assertEquals(
            "https://example.com/post",
            findFirstWebUrl("Read https://example.com/post.")
        )
    }

    @Test
    fun `stops before a chinese parenthesis`() {
        assertEquals(
            "https://example.com/post",
            findFirstWebUrl("参考（https://example.com/post）")
        )
    }

    @Test
    fun `keeps query string and fragment`() {
        assertEquals(
            "https://example.com/a?b=1&c=2#frag",
            findFirstWebUrl("https://example.com/a?b=1&c=2#frag")
        )
    }

    @Test
    fun `returns the first of several urls`() {
        assertEquals(
            "https://a.example.com/1",
            findFirstWebUrl("https://a.example.com/1 和 https://b.example.com/2")
        )
    }

    @Test
    fun `ignores text without a url`() {
        assertNull(findFirstWebUrl("今天天气不错，记一条灵感"))
    }

    @Test
    fun `ignores ftp and bare domains`() {
        assertNull(findFirstWebUrl("ftp://example.com/file"))
        assertNull(findFirstWebUrl("example.com/post"))
    }

    @Test
    fun `does not treat a bare scheme as a url`() {
        assertNull(findFirstWebUrl("https://"))
    }
}
