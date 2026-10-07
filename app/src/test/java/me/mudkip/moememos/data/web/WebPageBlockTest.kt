package me.mudkip.moememos.data.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 网页正文追加进笔记时的格式。
 *
 * 这段文字会原样出现在用户的笔记里，改动它等于改动用户已经看习惯的东西，
 * 所以把形状断言死——尤其是「标题首行、地址第二行、正文紧随」这三条。
 */
class WebPageBlockTest {

    private val time = LocalDateTime.of(2026, 10, 7, 22, 15)

    private fun block(
        title: String = "标题",
        url: String = "https://example.com/post",
        markdown: String = "正文内容",
        note: String? = null,
    ) = buildWebPageBlock(
        label = "网页",
        fetchedAt = time,
        title = title,
        url = url,
        markdown = markdown,
        note = note,
    )

    @Test
    fun `title is the first line`() {
        assertTrue(block().startsWith("\n\n#### 标题\n"))
    }

    @Test
    fun `address is the second line with the fetch time trailing it`() {
        assertTrue(
            block().contains(
                "#### 标题\nhttps://example.com/post  [网页 22:15]\n"
            )
        )
    }

    @Test
    fun `title falls back to the url when the page has none`() {
        assertTrue(
            block(title = "   ").contains(
                "#### https://example.com/post\nhttps://example.com/post  [网页 22:15]\n"
            )
        )
    }

    @Test
    fun `body is separated from the header by a blank line`() {
        assertTrue(block(markdown = "\n\n正文内容\n\n").endsWith("[网页 22:15]\n\n正文内容"))
    }

    @Test
    fun `square brackets in the title stay literal`() {
        val escaped = block(title = "[视频] 年度总结")

        assertTrue(escaped.startsWith("\n\n#### \\[视频\\] 年度总结\n"))
        assertFalse(escaped.contains("](https://"))
    }

    @Test
    fun `truncation note is quoted right under the address`() {
        assertTrue(
            block(note = "…文章过长").contains(
                "[网页 22:15]\n\n> …文章过长\n\n正文内容"
            )
        )
    }

    @Test
    fun `blank note leaves no stray quote line`() {
        val noNote = block(note = "  ")

        assertFalse(noNote.contains("\n> \n"))
        assertTrue(noNote.contains("[网页 22:15]\n\n正文内容"))
    }

    @Test
    fun `header takes exactly two non blank lines`() {
        val lines = block().trimStart('\n').lines()

        assertEquals("#### 标题", lines[0])
        assertEquals("https://example.com/post  [网页 22:15]", lines[1])
        assertEquals("", lines[2])
        assertEquals("正文内容", lines[3])
    }

    // -------------------------------------------------- 追加时那条裸地址要不要留

    private val url = "https://example.com/post"

    @Test
    fun `a body that is only the pasted address is dropped`() {
        assertEquals("", stripSoleUrlBody(url, url, url))
    }

    @Test
    fun `a trailing slash does not count as a difference`() {
        assertEquals("", stripSoleUrlBody("$url/", url, url))
        assertEquals("", stripSoleUrlBody(url, "$url/", "$url/"))
    }

    @Test
    fun `the address typed before a redirect counts too`() {
        assertEquals(
            "",
            stripSoleUrlBody("https://exa.mp/abc", "https://exa.mp/abc", "https://example.com/post")
        )
    }

    @Test
    fun `the resolved address counts when the page redirected`() {
        assertEquals(
            "",
            stripSoleUrlBody("https://example.com/post", "https://exa.mp/abc", "https://example.com/post")
        )
    }

    @Test
    fun `a body that says anything else is left alone`() {
        val text = "$url 这篇不错"

        assertEquals(text, stripSoleUrlBody(text, url, url))
        assertEquals("推荐看 $url", stripSoleUrlBody("推荐看 $url", url, url))
        assertEquals("看这个：\n$url\n记得存档", stripSoleUrlBody("看这个：\n$url\n记得存档", url, url))
    }

    @Test
    fun `an unrelated body is left alone`() {
        val text = "https://other.example.com/post"

        assertEquals(text, stripSoleUrlBody(text, url, url))
    }

    @Test
    fun `surrounding blank lines do not stop the match`() {
        assertEquals("", stripSoleUrlBody("\n\n  $url  \n\n", url, url))
    }

    @Test
    fun `an empty body stays empty`() {
        assertEquals("", stripSoleUrlBody("", url, url))
        assertEquals("   ", stripSoleUrlBody("   ", url, url))
    }
}
