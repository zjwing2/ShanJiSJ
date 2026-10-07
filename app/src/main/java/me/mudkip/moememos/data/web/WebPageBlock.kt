package me.mudkip.moememos.data.web

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 网页正文落进笔记时的排版。
 *
 * 和 [me.mudkip.moememos.data.transcription.buildTranscriptionBlock] 同一个理由单独成纯函数：
 * 这是用户唯一**直接看到**的产出格式，最容易被后续改动碰坏，值得被断言固定下来。
 *
 * 形态（[label] 取当前语言的「网页」）：
 * ```
 *
 * #### 文章标题
 * https://example.com/post  [网页 22:15]
 *
 * 正文……
 * ```
 *
 * 标题写成四级标题而不是纯文本：卡片上它是唯一被露出来的东西，跟正文字号
 * 一样就找不出来了。级别定在 `####` 是跟内页（详情页）对齐——内页标题一律
 * 按四级显示，存盘也存四级，两边就不用互相换算；卡片上再单独降一级到五级
 * （见 [CARD_HEADING_LEVEL]），一屏要扫十几条，卡片里的标题可以更小一点。
 */
internal fun buildWebPageBlock(
    label: String,
    fetchedAt: LocalDateTime,
    title: String,
    url: String,
    markdown: String,
    note: String? = null,
): String = buildString {
    // 标题独占首行。网页标题里偶尔带方括号，不转义的话这一行会被渲染成链接，
    // 所以要保证它始终是纯文本。
    append("\n\n#### ").append(escapePlainText(title.ifBlank { url })).append('\n')
    append(url)
    append("  [").append(label).append(' ').append(CLOCK.format(fetchedAt)).append("]\n")
    if (!note.isNullOrBlank()) {
        append("\n> ").append(note).append('\n')
    }
    append('\n')
    append(markdown.trim())
}

/** 让 [text] 在 Markdown 里保持字面显示：只转义最容易被当成语法的方括号和反斜杠。 */
private fun escapePlainText(text: String): String = text
    .replace("\\", "\\\\")
    .replace("[", "\\[")
    .replace("]", "\\]")

/**
 * 追加网页正文时，正文里那条裸地址要不要留。
 *
 * 多数用法是「粘一个链接 → 点读取正文」，这时候那行地址只是**触发抓取的动作**，
 * 不是成稿内容；而块头第二行本来就带着出处，两边都留就成了同一个地址出现两次。
 * 所以只剩这一行时把它拿掉——成稿从标题开始，干净。
 *
 * 判定刻意收得很紧：整段文本去空白后**正好等于**请求地址或抓取后的最终地址
 * （尾斜杠差异算同一个）才动手。正文里还夹着别的字就原样返回：
 * 猜用户想删什么，比留着一行重复地址糟得多。
 */
internal fun stripSoleUrlBody(
    text: String,
    requestedUrl: String,
    resolvedUrl: String,
): String {
    val body = text.trim()
    if (body.isEmpty()) return text

    val matches = sequenceOf(requestedUrl, resolvedUrl).any { candidate ->
        val trimmed = candidate.trim().trimEnd('/')
        trimmed.isNotEmpty() && body.trimEnd('/').equals(trimmed, ignoreCase = true)
    }

    return if (matches) "" else text
}

/**
 * 一次抓取要交给输入页的东西：拼好的块 + 用来判断要不要删掉用户输入那行的地址。
 *
 * 两个地址都带上：请求地址是用户手打的（可能带跳转前的形式），
 * 最终地址是重定向后拿到的，两种都该算「同一篇文章」。
 */
data class WebLinkAppend(
    val block: String,
    val requestedUrl: String,
    val resolvedUrl: String,
)

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
