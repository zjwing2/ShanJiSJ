package me.mudkip.moememos.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情页长文的渐进渲染。
 *
 * 切多长直接决定进页面掉不掉帧，切在哪决定会不会把代码块/表格劈成两半，
 * 两件事都只能靠断言守住——肉眼看是「还能滑」，帧数已经是另一回事了。
 */
class MarkdownProgressiveTest {

    @Test
    fun `short content is rendered whole with nothing left over`() {
        val (text, hasMore) = splitProgressiveContent("# 标题\n\n只有两行正文")

        assertEquals("# 标题\n\n只有两行正文", text)
        assertFalse(hasMore)
    }

    @Test
    fun `a long note is cut short and keeps the opening`() {
        val body = List(200) { "第 $it 行正文" }.joinToString("\n\n")
        val (text, hasMore) = splitProgressiveContent(body)

        assertTrue(hasMore)
        assertTrue(text.startsWith("第 0 行正文"))
        assertTrue(text.lines().filter { it.isNotBlank() }.size <= PROGRESSIVE_FIRST_LINES)
        assertTrue(text.length < body.length)
    }

    @Test
    fun `the cut never splits a code block in half`() {
        val code = "```\n" + List(30) { "line $it" }.joinToString("\n") + "\n```"
        val content = List(40) { "第 $it 行" }.joinToString("\n\n") + "\n\n" + code
        val (text, hasMore) = splitProgressiveContent(content)

        assertTrue(hasMore)
        // 出现了开头的 ``` 就必须同时收尾，否则渲染出来是半截代码块
        val fences = text.count { it == '`' }
        assertTrue(fences % 6 == 0)
    }

    @Test
    fun `a first block longer than a screen still shows something`() {
        val oneHugeBlock = "```\n" + List(200) { "line $it" }.joinToString("\n") + "\n```"
        val (text, hasMore) = splitProgressiveContent(oneHugeBlock)

        assertTrue(text.isNotBlank())
        assertEquals(PROGRESSIVE_FIRST_LINES, text.lines().size)
        assertTrue(hasMore)
    }

    @Test
    fun `blank text has nothing to expand`() {
        val (text, hasMore) = splitProgressiveContent("")

        assertEquals("", text)
        assertFalse(hasMore)
    }
}
