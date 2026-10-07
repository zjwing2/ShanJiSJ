package me.mudkip.moememos.data.web

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import com.vladsch.flexmark.util.data.MutableDataSet
import net.dankito.readability4j.Readability4J
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayInputStream

/**
 * 从一份 HTML 里抽出可以写进笔记的正文。
 *
 * 从 [WebPageFetcher] 里拆出来，是为了让这段逻辑**不依赖网络**就能测：
 * 正文提取里最容易被改坏的两件事——相对地址要变绝对、图片只留链接——
 * 全在这里，值得用真实 HTML 断言，而不是等抓到某个真站点才发现。
 */
internal object WebArticleExtractor {

    private const val MAX_TEXT_CHARS = 30_000

    /**
     * 正文至少要有这么多字符才算「读到了」。
     *
     * 阈值定得低：真实文章（哪怕是短讯、诗）都远超它，而空壳页面剩下的标题、
     * 「加载中」之类远低于它。
     */
    private const val MIN_CONTENT_CHARS = 40

    /**
     * 降级成摘要时，摘要至少要有这么长才值得存。
     *
     * 定在 10：一句「登录查看更多」这种占位文案会被挡掉，
     * 而真正的 og:description 通常几十到上百字。
     */
    private const val MIN_SUMMARY_CHARS = 10

    private val markdownConverter = run {
        val options = MutableDataSet()
        // 不输出元素的 id 属性：否则标题会变成「封面图 {#main-content}」，
        // 一堆锚点残渣混在正文里。
        options.set(FlexmarkHtmlConverter.OUTPUT_ATTRIBUTES_ID, false)
        // 标题用 `#` 而不是下划线式：笔记里要能一眼看出层级，也方便后续编辑。
        options.set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false)
        FlexmarkHtmlConverter.builder(options).build()
    }

    /**
     * @param bytes 原始响应体。转成字符串这一步交给 jsoup——它认得 BOM 和
     *   `<meta charset>`，GBK 页面因此也能正常读。
     * @param baseUrl 最终地址，用来把相对地址补全。
     * @return 抽不出正文时返回 null（页面可能是纯 JS 渲染的空壳）。
     */
    fun extract(bytes: ByteArray, baseUrl: String): WebArticle? {
        if (bytes.isEmpty()) return null

        val parsed = try {
            Jsoup.parse(ByteArrayInputStream(bytes), null, baseUrl)
        } catch (_: Exception) {
            return null
        }

        val article = try {
            Readability4J(baseUrl, parsed).parse()
        } catch (_: Exception) {
            null
        }

        val content = article?.articleContent ?: parsed.body()

        // JS 渲染的页面（SPA）交回来的是空壳，抽到的东西往往只剩站名或一句
        // 「Loading」。这种内容写进笔记毫无价值，用户还以为是抓失败了，
        // 不如直接算作「没提取到正文」。
        if (content.text().trim().length < MIN_CONTENT_CHARS) {
            // 但空壳页不等于没价值：多数站点的 og:description 是服务端就写好的，
            // JS 不跑也在。抓不到全文时退一步存摘要 + 链接，总比只留一句报错有用。
            return extractSummary(parsed, baseUrl)
        }

        val markdown = try {
            markdownConverter.convert(sanitize(content, baseUrl))
        } catch (_: Exception) {
            return extractSummary(parsed, baseUrl)
        }

        val cleaned = cleanMarkdown(markdown)
        if (cleaned.isBlank()) return extractSummary(parsed, baseUrl)

        val overlong = cleaned.length > MAX_TEXT_CHARS
        return WebArticle(
            url = baseUrl,
            title = article?.title?.trim()?.takeIf { it.isNotEmpty() } ?: parsed.title().trim(),
            byline = article?.byline?.trim()?.takeIf { it.isNotEmpty() },
            markdown = if (overlong) truncate(cleaned) else cleaned,
            truncated = overlong,
        )
    }

    /**
     * 正文抓不到时的退路：只拿页面自带的标题和摘要。
     *
     * 门槛是**必须有摘要**才收。标题单独存在时多半不是文章——
     * 反爬验证页（如百度「百度安全验证」）就只有一个标题，把那个存进笔记
     * 比报错还糟：用户会以为自己存了一篇叫「百度安全验证」的文章。
     *
     * @return 没有可用摘要时返回 null，交给调用方按「没抓到」处理。
     */
    private fun extractSummary(parsed: Document, baseUrl: String): WebArticle? {
        val description = metaContent(parsed, "og:description", "twitter:description", "description")
            ?.trim()
            ?.takeIf { it.length >= MIN_SUMMARY_CHARS }
            ?: return null

        val title = metaContent(parsed, "og:title", "twitter:title")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: parsed.title().trim()

        return WebArticle(
            url = baseUrl,
            title = title,
            byline = null,
            markdown = description,
            truncated = false,
            summaryOnly = true,
        )
    }

    /** 按顺序找第一个有内容的 meta：og 优先，最后退回标准 description。 */
    private fun metaContent(parsed: Document, vararg keys: String): String? {
        for (key in keys) {
            val value = parsed.selectFirst("meta[property='$key']")?.attr("content")
                ?: parsed.selectFirst("meta[name='$key']")?.attr("content")
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    /**
     * 转 Markdown 之前先收拾一遍 DOM。
     *
     * 所有 `src`/`href` 换成绝对地址：正文里的图片和链接在 Markdown 里只剩 URL，
     * 相对地址一旦离开原页面就没有意义（`/img/a.png` 指向哪里只有原站知道）。
     */
    private fun sanitize(content: Element, baseUrl: String): String {
        content.select("script, style, noscript, iframe, form, svg, video, audio, canvas").remove()

        content.select("img").forEach { img ->
            val width = img.attr("width").toIntOrNull()
            val height = img.attr("height").toIntOrNull()
            // 1×1 的追踪像素没有任何阅读价值，留着只会污染正文
            if ((width != null && width <= 2) || (height != null && height <= 2)) {
                img.remove()
                return@forEach
            }
            val absolute = img.absUrl("src").ifBlank { img.attr("src") }
            if (absolute.isBlank()) {
                img.remove()
            } else {
                img.attr("src", absolute)
                img.removeAttr("srcset")
            }
        }

        content.select("a").forEach { a ->
            val absolute = a.absUrl("href")
            if (absolute.isNotBlank()) {
                a.attr("href", absolute)
            }
        }

        return content.outerHtml().ifBlank { content.html() }
    }

    /** 折叠多余空行、去掉行尾空白：转换器会留下成片的空行，直接进笔记很难看。 */
    private fun cleanMarkdown(markdown: String): String = markdown
        .replace("\r\n", "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .trim()

    /** 太长的文章按段落截断，尽量不在句子中间断开。 */
    private fun truncate(text: String): String =
        text.take(MAX_TEXT_CHARS).substringBeforeLast("\n\n").ifBlank { text.take(MAX_TEXT_CHARS) }
}
