package me.mudkip.moememos.util

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode

/** 列表卡片上的标题层级：旧笔记存 `##`、新笔记存 `####`，卡片上一律按五级显示。 */
const val CARD_HEADING_LEVEL = 5

/** 详情页（内页）的标题层级：不管存的是几级，打开后一律按四级显示。 */
const val DETAIL_HEADING_LEVEL = 4

/**
 * 把正文里的 ATX 标题统一改成指定层级（`## 标题` → `#### 标题`）。
 *
 * 为什么要改：早期版本存过 `## 标题`，后来存过 `######`，同一屏里标题有大有小，
 * 看着像渲染坏了。改**渲染前的文本**而不是改库里的字号映射，是因为卡片和内页
 * 要不同的层级（卡片五级、内页四级），只有文本这一层能分开。
 *
 * 用 AST 找标题而不是按行首 `#` 正则替换：代码块、行内代码里的 `# 注释`
 * 长得和标题一模一样，正则会把源码改坏；AST 里它们属于 CODE_FENCE 节点，
 * 压根不会走到 ATX 分支。
 *
 * 只动 `#` 的个数，标题文字、闭合序列（`## 标题 ##`）一律原样保留。
 */
fun normalizeHeadingLevel(markdownText: String, level: Int): String {
    if (markdownText.isEmpty()) return markdownText

    val edits = ArrayList<Triple<Int, Int, String>>()
    collectHeadingEdits(parseMarkdown(markdownText), markdownText, level, edits)
    if (edits.isEmpty()) return markdownText

    val result = StringBuilder(markdownText.length + edits.size * 4)
    var cursor = 0
    for ((start, end, replacement) in edits) {
        if (start < cursor) continue
        result.append(markdownText, cursor, start).append(replacement)
        cursor = end
    }
    result.append(markdownText, cursor, markdownText.length)
    return result.toString()
}

private fun collectHeadingEdits(
    node: ASTNode,
    markdownText: String,
    level: Int,
    edits: MutableList<Triple<Int, Int, String>>,
) {
    if (isAtxHeading(node)) {
        val raw = markdownText.substring(node.startOffset, node.endOffset)
        val markerEnd = raw.indexOfFirst { it != '#' }
        if (markerEnd > 0 && markerEnd <= 6) {
            val marker = "#".repeat(level)
            edits.add(
                Triple(
                    node.startOffset,
                    node.startOffset + markerEnd,
                    marker,
                )
            )
        }
        return
    }
    for (child in node.children) {
        collectHeadingEdits(child, markdownText, level, edits)
    }
}

private fun isAtxHeading(node: ASTNode): Boolean {
    return node.type == MarkdownElementTypes.ATX_1 ||
        node.type == MarkdownElementTypes.ATX_2 ||
        node.type == MarkdownElementTypes.ATX_3 ||
        node.type == MarkdownElementTypes.ATX_4 ||
        node.type == MarkdownElementTypes.ATX_5 ||
        node.type == MarkdownElementTypes.ATX_6
}
