package me.mudkip.moememos.util

import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode

/**
 * 详情页的长文一次性铺开会把主线程压死，所以先只渲染前若干行。
 *
 * 起因是一条抓下来的周刊：483 行、57 张远程图，进详情页时 Choreographer
 * 直接报掉 43 帧——Compose 要把几百行一次组合完，图片还各自触发一次重组。
 * 同样的手机打开一条纯文本笔记是 0 掉帧，所以卡的不是机器，是内容量。
 *
 * 按**块**切而不是按字符切：字符切会把代码块、表格劈成两半，
 * 前后两截各自渲染出来是错乱的。块要么整块留下，要么整块挪到「展开全文」之后。
 *
 * 24 行是实测下来的数：门槛放到 60 行时首屏仍有 9 张远程图，进页面照样掉一百多帧
 * ——每张图加载完高度一变，整列要重新布局一次，图越多越贵。收到 24 行（约 3 张图）
 * 才真正不卡。真凶是图片节点的数量，不是文字行数。
 */
const val PROGRESSIVE_FIRST_LINES = 24

/**
 * 每次点「继续展开」追加多少行。
 *
 * 一次性铺完 483 行实测掉 67 帧；拆成每批 120 行后每次只有十几帧，
 * 卡的感觉就没了。行数是拿帧数换出来的，不是拍脑袋定的。
 */
const val PROGRESSIVE_CHUNK_LINES = 120

/**
 * @return 首屏要渲染的文本 + 后面是否还有内容（true 时需要「展开全文」）。
 */
fun splitProgressiveContent(
    markdownText: String,
    firstLines: Int = PROGRESSIVE_FIRST_LINES,
): Pair<String, Boolean> {
    if (firstLines <= 0) return Pair("", markdownText.isNotBlank())

    val blocks = parseMarkdown(markdownText)
        .children
        .filterNot { isProgressiveBlankBlock(it) }

    var used = 0
    for (block in blocks) {
        val blockLines = markdownText.substring(block.startOffset, block.endOffset)
            .lines()
            .count { it.isNotBlank() }
            .coerceAtLeast(1)

        // 这一块放不下就整块留到展开之后：宁可首屏短一点，也不渲染半张表
        if (used + blockLines > firstLines) {
            val head = markdownText.substring(0, block.startOffset).trimEnd()
            if (head.isNotBlank()) return Pair(head, true)
            // 第一块本身就超过一屏（比如一个超长代码块）：退回按行切，
            // 总比给用户一片空白强
            val byLine = markdownText.lines().take(firstLines).joinToString("\n")
            return Pair(byLine, byLine.length < markdownText.trimEnd().length)
        }
        used += blockLines
    }

    return Pair(markdownText, false)
}

private fun isProgressiveBlankBlock(node: ASTNode): Boolean {
    return node.type == MarkdownTokenTypes.EOL ||
        node.type == MarkdownTokenTypes.WHITE_SPACE ||
        node.startOffset >= node.endOffset
}
