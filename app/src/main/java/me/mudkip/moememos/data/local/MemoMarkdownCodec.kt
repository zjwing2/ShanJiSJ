package me.mudkip.moememos.data.local

import me.mudkip.moememos.data.model.MemoVisibility
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Memo <-> Markdown 文件的双向编解码（文件夹同步专用）。
 *
 * 文件形态：
 * ```
 * ---
 * id: 3f9c2a1e-8b7d-4c2a-9e1f-0a5b6c7d8e9f
 * created: 2026-10-06T11:36:23Z
 * updated: 2026-10-06T12:02:10Z
 * pinned: false
 * archived: false
 * visibility: PRIVATE
 * tags: [灵感, 待办]
 * attachments: ["voice-20261006-215812.m4a"]
 * app: moememos-md
 * fm: 1
 * ---
 *
 * 正文内容……
 * ```
 *
 * 设计约束：
 * 1. front matter 是唯一元数据来源，正文原样保留，不转义、不重排；
 * 2. 解析必须容忍外部编辑器（Obsidian / VS Code）改动引号、空格、字段顺序；
 * 3. 任何解析失败都不允许抛异常——最坏情况退化为“整篇都是正文”，宁可多一条笔记也不丢内容；
 * 4. 只有当 `---` 块里出现 `id:` 或 `app:` 时才认定为 front matter，避免把以 `---`
 *    开头（水平线 / YAML）的普通笔记当成元数据吃掉；
 * 5. `attachments` 只是给人和别的工具看的清单，解析时忽略——它永远由本地资源表重新生成，
 *    因此外部删掉这一行不会有任何后果。
 */
object MemoMarkdownCodec {

    const val MARKER = "---"
    const val APP_TAG = "moememos-md"
    const val FM_VERSION = "1"

    /** 一条 memo 的文件表示。 */
    data class Parsed(
        val id: String?,
        val content: String,
        val created: Instant?,
        val updated: Instant?,
        val pinned: Boolean,
        val archived: Boolean,
        val visibility: MemoVisibility,
    )

    /** 实体 -> Markdown 文本。 */
    fun encode(
        id: String,
        content: String,
        created: Instant,
        updated: Instant,
        pinned: Boolean,
        archived: Boolean,
        visibility: MemoVisibility,
        tags: List<String> = emptyList(),
        attachments: List<String> = emptyList(),
    ): String = buildString {
        append(MARKER).append('\n')
        append("id: ").append(id).append('\n')
        append("created: ").append(created.toString()).append('\n')
        append("updated: ").append(updated.toString()).append('\n')
        append("pinned: ").append(pinned).append('\n')
        append("archived: ").append(archived).append('\n')
        append("visibility: ").append(visibility.name).append('\n')
        if (tags.isNotEmpty()) {
            append("tags: [").append(tags.joinToString(", ")).append("]\n")
        }
        // 只在 front matter 里登记附件名，**绝不往正文里插链接**：正文是用户的，
        // 混入生成内容会被对账引擎当成外部编辑，反而用生成物覆盖掉用户写的东西。
        // 附件实体本身在 `attachments/<id 前 8 位>/` 目录里。
        if (attachments.isNotEmpty()) {
            append("attachments: [")
                .append(attachments.joinToString(", ") { "\"$it\"" })
                .append("]\n")
        }
        append("app: ").append(APP_TAG).append('\n')
        append("fm: ").append(FM_VERSION).append('\n')
        append(MARKER).append('\n')
        append('\n')
        append(content.trim('\n')).append('\n')
    }

    /** Markdown 文本 -> Parsed。永不抛异常。 */
    fun decode(text: String): Parsed {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        if (lines.isEmpty() || lines[0].trim() != MARKER) return plain(normalized)

        var close = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == MARKER) {
                close = i
                break
            }
        }
        if (close < 0) return plain(normalized)

        val raw = LinkedHashMap<String, String>()
        for (i in 1 until close) {
            val line = lines[i]
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim().trim('"', '\'')
            raw[key] = value
        }

        // 只有出现自家字段才认作 front matter，否则整篇按正文处理
        if (!raw.containsKey("id") && raw["app"] != APP_TAG) return plain(normalized)

        val body = if (close + 1 >= lines.size) {
            ""
        } else {
            lines.subList(close + 1, lines.size)
                .dropWhile { it.isBlank() }
                .joinToString("\n")
                .trim('\n')
        }

        return Parsed(
            id = raw["id"]?.takeIf { it.isNotBlank() },
            content = body,
            created = raw["created"]?.let(::parseInstant),
            updated = raw["updated"]?.let(::parseInstant),
            pinned = raw["pinned"].toBooleanFlag(),
            archived = raw["archived"].toBooleanFlag(),
            visibility = raw["visibility"]
                ?.let { runCatching { MemoVisibility.valueOf(it.uppercase()) }.getOrNull() }
                ?: MemoVisibility.PRIVATE,
        )
    }

    /** 没有合法 front matter 的裸 Markdown（外部新建的文件）：整篇即正文。 */
    private fun plain(text: String) = Parsed(
        id = null,
        content = text.trim(),
        created = null,
        updated = null,
        pinned = false,
        archived = false,
        visibility = MemoVisibility.PRIVATE,
    )

    private fun String?.toBooleanFlag(): Boolean =
        this != null && (this.equals("true", true) || this == "1" || this.equals("yes", true))

    private fun parseInstant(value: String): Instant? {
        val trimmed = value.trim()
        runCatching { return Instant.parse(trimmed) }
        runCatching { return OffsetDateTime.parse(trimmed).toInstant() }
        runCatching {
            return LocalDateTime.parse(trimmed).atZone(ZoneId.systemDefault()).toInstant()
        }
        runCatching {
            val epoch = trimmed.toLong()
            return Instant.ofEpochSecond(if (epoch > 100_000_000_000L) epoch / 1000 else epoch)
        }
        return null
    }
}
