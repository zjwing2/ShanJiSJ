package me.mudkip.moememos.util

/**
 * 从 `![](https://…)` 这样的源码里取出地址。
 *
 * 详情页的图片改成按需加载后，得先知道这张图到底要加载什么；
 * 这里刻意只认 Markdown 图片语法和裸地址两种形态，认不出来就返回 null
 * ——拿不到地址的图宁可留个占位，也不要拿整段源码去当 URL 请求。
 */
private val MARKDOWN_IMAGE_SYNTAX = Regex("!\\[[^\\]]*\\]\\(\\s*<?([^\\s)>]+)>?")

fun extractMarkdownImageLink(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null

    MARKDOWN_IMAGE_SYNTAX.find(text)?.let { return it.groupValues[1].trim() }

    return if (text.startsWith("http://") || text.startsWith("https://")) {
        text.substringBefore('"').substringBefore('\'').trim()
    } else {
        null
    }
}
