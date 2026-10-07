package me.mudkip.moememos.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 灵感列表的卡片上到底露出多少内容。
 *
 * 这个数直接决定一屏能扫几条，改坏了（露太多/截断标记丢了）从代码上看不出来，
 * 只有盯着屏幕量行数才发现，所以把「几行」和「有没有被截」都断言死。
 */
class MarkdownPreviewTest {

    @Test
    fun `a note with a heading shows only the heading`() {
        val (text, truncated) = extractPreviewContent(
            """
            #### 标题
            https://example.com/post  [网页 22:15]

            第一行内容
            第二行内容
            """.trimIndent()
        )

        assertEquals("##### 标题", text)
        assertFalse(text.contains("第一行内容"))
        // 标题后面还有正文，所以要留「查看更多」的入口
        assertTrue(truncated)
    }

    @Test
    fun `the heading is used even when it is not the first block`() {
        val (text, _) = extractPreviewContent("随手记一句\n\n## 后来补的标题\n\n正文")

        assertEquals("##### 后来补的标题", text)
    }

    @Test
    fun `a note that is only a heading is not marked as truncated`() {
        val (text, truncated) = extractPreviewContent("#### 一句话灵感")

        assertEquals("##### 一句话灵感", text)
        assertFalse(truncated)
    }

    @Test
    fun `a heading that fits in three lines is kept whole`() {
        // 约 60 字：折算下来不到 3 行，不应被掐断
        val heading = "科技爱好者周刊（第 286 期）：蓝色指示灯的解决方案 - 阮一峰的网络日志"
        val (text, _) = extractPreviewContent("##### $heading\n\n正文")

        assertEquals("##### $heading", text)
    }

    @Test
    fun `a heading longer than three lines is cut with an ellipsis`() {
        val (text, truncated) = extractPreviewContent("##### " + "很长的标题".repeat(50))

        assertTrue(text.endsWith("…"))
        // 3 行容量（34 字 × 3）+ 前缀，掐断后不会更长
        assertTrue(text.removePrefix("##### ").length <= 34 * 3 + 1)
        assertTrue(truncated)
    }

    @Test
    fun `without a heading the first three body lines are kept`() {
        val (text, truncated) = extractPreviewContent(
            """
            第一行内容

            第二行内容
            第三行内容
            第四行内容
            """.trimIndent()
        )

        assertEquals(3, text.lines().size)
        assertFalse(text.contains("第四行内容"))
        assertTrue(truncated)
    }

    @Test
    fun `short content is kept whole and not marked as truncated`() {
        val (text, truncated) = extractPreviewContent("只有一句话的灵感")

        assertEquals("只有一句话的灵感", text)
        assertFalse(truncated)
    }

    @Test
    fun `a body line longer than one screen line is cut with an ellipsis`() {
        val longLine = "很长的一段话".repeat(30)
        val (text, truncated) = extractPreviewContent(longLine)

        assertTrue(text.endsWith("…"))
        assertTrue(truncated)
    }

    @Test
    fun `a code block counts as a single line`() {
        val (text, truncated) = extractPreviewContent(
            """
            ```kotlin
            val a = 1
            val b = 2
            val c = 3
            ```
            """.trimIndent()
        )

        assertTrue(text.lines().size <= 3)
        assertFalse(text.contains("val c = 3"))
        assertTrue(truncated)
    }

    @Test
    fun `list items each count as one line`() {
        val (text, truncated) = extractPreviewContent(
            """
            - 第一项
            - 第二项
            - 第三项
            - 第四项
            """.trimIndent()
        )

        assertEquals(3, text.lines().size)
        assertFalse(text.contains("第四项"))
        assertTrue(truncated)
    }

    @Test
    fun `a list under a heading still gives way to the heading`() {
        val (text, _) = extractPreviewContent("### 购物清单\n\n- 牛奶\n- 鸡蛋")

        assertEquals("##### 购物清单", text)
    }

    @Test
    fun `blank text has nothing to show`() {
        val (text, truncated) = extractPreviewContent("")

        assertEquals("", text)
        assertFalse(truncated)
    }

    @Test
    fun `blank lines between blocks do not count`() {
        val (text, truncated) = extractPreviewContent("第一行\n\n\n\n第二行")

        assertEquals("第一行\n第二行", text)
        assertFalse(truncated)
    }
}
