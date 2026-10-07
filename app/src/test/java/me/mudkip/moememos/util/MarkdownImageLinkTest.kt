package me.mudkip.moememos.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 从 Markdown 图片语法里取地址。
 *
 * 取错了就会拿一段源码去发网络请求，或者整张图消失，
 * 而这两种错在界面上都表现为「图片没出来」，很难一眼看出是地址解析的问题。
 */
class MarkdownImageLinkTest {

    @Test
    fun `a plain image extracts the address`() {
        assertEquals(
            "https://cdn.example.com/a.webp",
            extractMarkdownImageLink("![](https://cdn.example.com/a.webp)")
        )
    }

    @Test
    fun `an alt text does not get in the way`() {
        assertEquals(
            "https://cdn.example.com/b.webp",
            extractMarkdownImageLink("![封面图](https://cdn.example.com/b.webp)")
        )
    }

    @Test
    fun `a title after the address is dropped`() {
        assertEquals(
            "https://cdn.example.com/c.webp",
            extractMarkdownImageLink("![](https://cdn.example.com/c.webp \"标题\")")
        )
    }

    @Test
    fun `angle bracket form is supported`() {
        assertEquals(
            "https://cdn.example.com/d.webp",
            extractMarkdownImageLink("![](<https://cdn.example.com/d.webp>)")
        )
    }

    @Test
    fun `a bare address is used as is`() {
        assertEquals(
            "https://cdn.example.com/e.webp",
            extractMarkdownImageLink("https://cdn.example.com/e.webp")
        )
    }

    @Test
    fun `ordinary text yields nothing`() {
        assertNull(extractMarkdownImageLink("这只是一段话"))
        assertNull(extractMarkdownImageLink("[链接](https://example.com)"))
        assertNull(extractMarkdownImageLink(""))
        assertNull(extractMarkdownImageLink("   "))
    }
}
