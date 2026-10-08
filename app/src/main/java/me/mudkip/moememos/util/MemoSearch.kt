package me.mudkip.moememos.util

import me.mudkip.moememos.data.local.entity.MemoEntity

/**
 * 判断一条笔记是否匹配搜索词。
 *
 * 覆盖用户关心的三类内容：
 * 1. 正文（含标题，因为标题本身就是 Markdown 文本的一部分）
 * 2. 标题行（heading）
 * 3. 自定义标签 / 关键词（#标签 形式）
 *
 * 采用忽略大小写的子串匹配，对中文同样有效。
 */
fun MemoEntity.matchesQuery(rawQuery: String): Boolean {
    val query = rawQuery.trim()
    if (query.isEmpty()) return true

    val content = content

    // 正文（含标题）直接包含即命中
    if (content.contains(query, ignoreCase = true)) return true

    // 自定义标签 / 关键词单独匹配，避免被正文噪声干扰
    if (extractCustomTags(content).any { it.contains(query, ignoreCase = true) }) return true

    return false
}
