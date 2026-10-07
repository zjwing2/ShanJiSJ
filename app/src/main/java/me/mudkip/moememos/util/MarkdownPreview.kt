package me.mudkip.moememos.util

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

/**
 * 列表卡片里显示的预览：有标题就只露标题，没标题才露正文前几行。
 *
 * 从 `MemoContent` 里搬出来是为了能单测——卡片上到底露出多少内容，
 * 是「界面好不好扫」的关键，靠肉眼看一次截图说服不了自己。
 *
 * 计数单位是**行**而不是字符：按字符算（以前是 500 字）在宽屏上能露五六行，
 * 窄屏上又只剩两行，卡片高度完全不受控制。列表的每一项、段落的每一行各算一行，
 * 行数上限一压住，卡片高矮就稳定了。
 */
private const val PREVIEW_LINE_MAX_CHARS = 80

/**
 * 卡片上一行标题大致装得下的字符数（H6 字号、中英混排，往保守了估）。
 *
 * 标题没有「源码行」可言——它在 `.md` 里永远是一行，占几行取决于渲染时折行，
 * 所以只能按字符数折算：上限 = [PREVIEW_MAX_LINES] 行 × 每行容量。
 * 字数不多就一两行，特别长的标题最多撑到 3 行就掐断。
 */
private const val HEADING_LINE_CHARS = 34

/** 卡片上默认露几行（无标题时的正文上限，也是标题的行数上限）。 */
const val PREVIEW_MAX_LINES = 3

/**
 * @return 预览文本 + 是否还有内容被省略（true 时卡片上要显示「查看更多」）。
 */
fun extractPreviewContent(
    markdownText: String,
    maxLines: Int = PREVIEW_MAX_LINES,
): Pair<String, Boolean> {
    if (maxLines <= 0) return Pair("", markdownText.isNotBlank())

    val blocks = parseMarkdown(markdownText)
        .children
        .filterNot { isPreviewBlankBlock(it) }

    if (blocks.isEmpty()) return Pair("", false)

    // 有标题就只露标题：一屏里几十条卡片，标题是唯一要被一眼扫到的东西，
    // 正文写得再好也是点进去才看的。留出正文反而把标题挤得找不着。
    //
    // 层级统一压到 [CARD_HEADING_LEVEL] 级再给卡片：早期版本存过 `## 标题`，
    // 后来存过 `######`，老笔记不会被改写，若照原样渲染，同一屏里标题会有两种大小。
    val headingIndex = blocks.indexOfFirst { isPreviewHeading(it) }
    if (headingIndex >= 0) {
        val headingText = markdownText
            .substring(blocks[headingIndex].startOffset, blocks[headingIndex].endOffset)
            .trim()
            .trimStart('#')
            .trim()
        if (headingText.isNotEmpty()) {
            // 标题字数不多就一两行，最多撑到 maxLines 行（按字符折算）再掐断
            val heading = clipTo(
                "${"#".repeat(CARD_HEADING_LEVEL)} $headingText",
                maxLines * HEADING_LINE_CHARS,
            )
            val omitted = blocks.size > 1 || heading.endsWith("…")
            return Pair(heading, omitted)
        }
    }

    val lines = blocks.flatMap { block ->
        markdownText.substring(block.startOffset, block.endOffset)
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    if (lines.isEmpty()) return Pair("", false)

    val kept = lines.take(maxLines).map(::clipLine)

    val truncated = lines.size > maxLines || kept.any { it.endsWith("…") }
    return Pair(kept.joinToString("\n"), truncated)
}

private fun clipLine(line: String): String = clipTo(line, PREVIEW_LINE_MAX_CHARS)

private fun clipTo(line: String, maxChars: Int): String {
    // 塞不下就掐断：一行几百字的段落会把卡片撑成半屏
    return if (line.length > maxChars) {
        line.take(maxChars).trimEnd() + "…"
    } else {
        line
    }
}

private fun isPreviewBlankBlock(node: ASTNode): Boolean {
    return node.type == MarkdownTokenTypes.EOL ||
        node.type == MarkdownTokenTypes.WHITE_SPACE ||
        node.startOffset >= node.endOffset
}

private fun isPreviewHeading(node: ASTNode): Boolean {
    return node.type == MarkdownElementTypes.ATX_1 ||
        node.type == MarkdownElementTypes.ATX_2 ||
        node.type == MarkdownElementTypes.ATX_3 ||
        node.type == MarkdownElementTypes.ATX_4 ||
        node.type == MarkdownElementTypes.ATX_5 ||
        node.type == MarkdownElementTypes.ATX_6
}
