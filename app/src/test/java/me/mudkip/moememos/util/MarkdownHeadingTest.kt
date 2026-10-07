package me.mudkip.moememos.util

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownHeadingTest {

    @Test
    fun `an old second level heading is lifted to the detail level`() {
        assertEquals("#### 标题", normalizeHeadingLevel("## 标题", DETAIL_HEADING_LEVEL))
    }

    @Test
    fun `a six level heading is lowered to the detail level`() {
        assertEquals("#### 标题", normalizeHeadingLevel("###### 标题", DETAIL_HEADING_LEVEL))
    }

    @Test
    fun `every heading in the note is normalized`() {
        val normalized = normalizeHeadingLevel(
            """
            # 一级
            正文

            ### 三级
            正文
            """.trimIndent(),
            DETAIL_HEADING_LEVEL,
        )

        assertEquals("#### 一级\n正文\n\n#### 三级\n正文", normalized)
    }

    @Test
    fun `the card level is one step smaller than the detail level`() {
        assertEquals("##### 标题", normalizeHeadingLevel("## 标题", CARD_HEADING_LEVEL))
    }

    @Test
    fun `a hash inside a code fence is left alone`() {
        // 代码块里的 `# 注释` 长得跟标题一样，改了就是把源码改坏
        val source = """
            ## 标题

            ```sh
            # 这是一条注释
            echo hi
            ```
        """.trimIndent()

        assertEquals(source.replace("## 标题", "#### 标题"), normalizeHeadingLevel(source, DETAIL_HEADING_LEVEL))
    }

    @Test
    fun `a hash tag is not treated as a heading`() {
        assertEquals("#work2026 今天", normalizeHeadingLevel("#work2026 今天", DETAIL_HEADING_LEVEL))
    }

    @Test
    fun `a closed heading keeps its trailing hashes`() {
        assertEquals("#### 标题 ##", normalizeHeadingLevel("## 标题 ##", DETAIL_HEADING_LEVEL))
    }

    @Test
    fun `text without a heading is returned untouched`() {
        val source = "就是一句普通的话\n\n再补一句"
        assertEquals(source, normalizeHeadingLevel(source, DETAIL_HEADING_LEVEL))
    }

    @Test
    fun `an empty note does not blow up`() {
        assertEquals("", normalizeHeadingLevel("", DETAIL_HEADING_LEVEL))
    }
}
