package me.mudkip.moememos.data.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真 HTML 进、Markdown 出。
 *
 * 这里跑的是完整的真实链路（jsoup → Readability4J → flexmark），
 * 而不是打桩：图片链接、相对地址、脚本清理这些事，只有真的过一遍库才知道
 * 会不会像预期那样留下来。
 */
class WebArticleExtractorTest {

    private fun page(body: String) = """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <title>文章标题</title>
        </head>
        <body>
            <nav><a href="/nav/1">首页</a><a href="/nav/2">关于</a></nav>
            <article id="content">
                $body
            </article>
            <script>console.log('tracking')</script>
        </body>
        </html>
    """.trimIndent()

    private val longParagraphs = (1..12).joinToString("") {
        "<p>这是第 $it 段正文，用来让正文提取算法认出这一块才是文章主体，" +
            "而不是页头的导航或者页尾的相关推荐。整段文字保持足够长度。</p>"
    }

    @Test
    fun `extracts the article text`() {
        val article = WebArticleExtractor.extract(
            page("<h1>文章标题</h1>$longParagraphs").toByteArray(),
            "https://example.com/post",
        )

        assertNotNull(article)
        assertTrue(article!!.markdown.contains("这是第 1 段正文"))
        assertTrue(article.markdown.contains("这是第 12 段正文"))
        assertTrue(article.title.contains("文章标题"))
    }

    @Test
    fun `image keeps its remote url as an absolute address`() {
        val article = WebArticleExtractor.extract(
            page("<h1>文章标题</h1>$longParagraphs<p><img src=\"/img/cover.png\" alt=\"封面\"></p>")
                .toByteArray(),
            "https://example.com/post",
        )

        assertNotNull(article)
        assertTrue(article!!.markdown.contains("https://example.com/img/cover.png"))
        // 图片不下载，只留链接：正文里不该出现任何本地路径
        assertFalse(article.markdown.contains("file://"))
    }

    @Test
    fun `relative links become absolute`() {
        val article = WebArticleExtractor.extract(
            page("$longParagraphs<p>详见<a href=\"/other\">另一篇</a></p>").toByteArray(),
            "https://example.com/post",
        )

        assertNotNull(article)
        assertTrue(article!!.markdown.contains("https://example.com/other"))
    }

    @Test
    fun `scripts are dropped`() {
        val article = WebArticleExtractor.extract(
            page("$longParagraphs<p>正文结束</p><script>alert(1)</script>").toByteArray(),
            "https://example.com/post",
        )

        assertNotNull(article)
        assertFalse(article!!.markdown.contains("alert(1)"))
        assertFalse(article.markdown.contains("console.log"))
    }

    @Test
    fun `tracking pixels are dropped`() {
        val article = WebArticleExtractor.extract(
            page("$longParagraphs<p>正文<img src=\"/px.gif\" width=\"1\" height=\"1\"></p>")
                .toByteArray(),
            "https://example.com/post",
        )

        assertNotNull(article)
        assertFalse(article!!.markdown.contains("px.gif"))
    }

    @Test
    fun `headings come out as hash-prefixed without id residue`() {
        val article = WebArticleExtractor.extract(
            page("""<h2 id="main-content">小节标题</h2>$longParagraphs""").toByteArray(),
            "https://example.com/post",
        )

        assertNotNull(article)
        // 标题要是 `## 小节标题`，不能是「小节标题 {#main-content} + 下划线」
        assertTrue(article!!.markdown.contains("## 小节标题"))
        assertFalse(article.markdown.contains("{#"))
    }

    @Test
    fun `empty response yields nothing`() {
        assertNull(WebArticleExtractor.extract(ByteArray(0), "https://example.com/post"))
    }

    @Test
    fun `javascript-only shell yields nothing readable`() {
        val html = """
            <!DOCTYPE html><html><head><title>Loading</title></head>
            <body><div id="app"></div></body></html>
        """.trimIndent()

        assertNull(WebArticleExtractor.extract(html.toByteArray(), "https://example.com/spa"))
    }

    @Test
    fun `gbk page is decoded instead of turned into mojibake`() {
        val html = """
            <!DOCTYPE html><html><head>
            <meta http-equiv="Content-Type" content="text/html; charset=gbk">
            <title>中文标题</title></head>
            <body><article id="content"><h1>中文标题</h1>$longParagraphs</article></body></html>
        """.trimIndent()

        val article = WebArticleExtractor.extract(
            html.toByteArray(charset("GBK")),
            "https://example.com/gbk",
        )

        assertNotNull(article)
        assertTrue(article!!.markdown.contains("这是第 1 段正文"))
    }

    // ---------------------------------------------- 抓不到正文时降级成「标题 + 摘要」

    /** 典型的 JS 空壳：正文靠脚本渲染，但 og 描述是服务端写好的。 */
    private fun shellPage(head: String = "") = """
        <!DOCTYPE html><html><head><title>Loading</title>
        $head
        </head><body><div id="app"></div></body></html>
    """.trimIndent()

    @Test
    fun `a js shell with a description falls back to title and summary`() {
        val article = WebArticleExtractor.extract(
            shellPage(
                """
                <meta property="og:title" content="真正的文章标题">
                <meta property="og:description" content="这是一段足够长的摘要，说明文章讲了什么，值得存进笔记。">
                """.trimIndent()
            ).toByteArray(),
            "https://example.com/spa",
        )

        assertNotNull(article)
        assertTrue(article!!.summaryOnly)
        assertEquals("真正的文章标题", article.title)
        assertTrue(article.markdown.contains("这是一段足够长的摘要"))
        // 链接不能丢：降级存的东西全靠这一行才能跳回原文
        assertEquals("https://example.com/spa", article.url)
    }

    @Test
    fun `a real article is never marked as summary only`() {
        val article = WebArticleExtractor.extract(
            page("<h1>文章标题</h1>$longParagraphs").toByteArray(),
            "https://example.com/post",
        )

        assertNotNull(article)
        assertFalse(article!!.summaryOnly)
    }

    @Test
    fun `a verification page with only a title is still nothing`() {
        // 百度这类反爬页就长这样：只有一个「百度安全验证」的标题，没有描述。
        // 收下它等于让用户以为自己存了一篇叫「百度安全验证」的文章。
        assertNull(
            WebArticleExtractor.extract(
                shellPage().toByteArray(),
                "https://baijiahao.baidu.com/s?id=1",
            )
        )
    }

    @Test
    fun `a description made of placeholder text is still nothing`() {
        assertNull(
            WebArticleExtractor.extract(
                shellPage("""<meta name="description" content="登录查看">""").toByteArray(),
                "https://example.com/spa",
            )
        )
    }
}
