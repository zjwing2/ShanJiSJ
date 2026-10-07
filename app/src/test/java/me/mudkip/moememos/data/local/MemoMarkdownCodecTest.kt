package me.mudkip.moememos.data.local

import me.mudkip.moememos.data.model.MemoVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 编解码是整条链路上唯一“纯逻辑”的部分，也是唯一能在没有设备的情况下验证的部分。
 * 这组用例覆盖：往返一致性、外部编辑器改坏 front matter、裸文件、以 `---` 开头的正文。
 */
class MemoMarkdownCodecTest {

    private val id = "3f9c2a1e-8b7d-4c2a-9e1f-0a5b6c7d8e9f"
    private val created = Instant.parse("2026-10-06T11:36:23Z")
    private val updated = Instant.parse("2026-10-06T12:02:10Z")

    @Test
    fun roundTrip_preservesEveryField() {
        val content = "今天试了 #灵感 的写法\n\n```kotlin\nval x = 1\n```"
        val text = MemoMarkdownCodec.encode(
            id = id, content = content, created = created, updated = updated,
            pinned = true, archived = false, visibility = MemoVisibility.PROTECTED,
            tags = listOf("灵感"),
        )
        val parsed = MemoMarkdownCodec.decode(text)

        assertEquals(id, parsed.id)
        assertEquals(content, parsed.content)
        assertEquals(created, parsed.created)
        assertEquals(updated, parsed.updated)
        assertTrue(parsed.pinned)
        assertEquals(false, parsed.archived)
        assertEquals(MemoVisibility.PROTECTED, parsed.visibility)
    }

    @Test
    fun contentStartingWithHorizontalRule_isNotEatenAsFrontMatter() {
        val content = "---\n\n上面是三条杠，不是 front matter。"
        val parsed = MemoMarkdownCodec.decode(content)
        assertNull(parsed.id)
        assertEquals(content.trim(), parsed.content)
    }

    @Test
    fun yamlFrontMatterFromAnotherApp_isKeptAsContent() {
        val text = "---\ntitle: 别的软件的笔记\n---\n正文"
        val parsed = MemoMarkdownCodec.decode(text)
        // 没有 id / app 字段 => 视作普通正文，宁可整篇留下也不吞掉内容
        assertNull(parsed.id)
        assertTrue(parsed.content.contains("title: 别的软件的笔记"))
    }

    @Test
    fun plainFile_hasNoIdAndKeepsWholeBody() {
        val parsed = MemoMarkdownCodec.decode("# 随手记的一条\n\n没 front matter")
        assertNull(parsed.id)
        assertNull(parsed.created)
        assertEquals(MemoVisibility.PRIVATE, parsed.visibility)
        assertEquals("# 随手记的一条\n\n没 front matter", parsed.content)
    }

    @Test
    fun crlfAndMessySpacing_areTolerated() {
        val text = "---\r\nid :   $id  \r\ncreated: 2026-10-06T11:36:23Z\r\n" +
            "updated: \"2026-10-06T12:02:10Z\"\r\npinned: TRUE\r\nvisibility: public\r\n" +
            "app: moememos-md\r\n---\r\n正文\r\n"
        val parsed = MemoMarkdownCodec.decode(text)
        assertEquals(id, parsed.id)
        assertEquals(created, parsed.created)
        assertEquals(updated, parsed.updated)
        assertTrue(parsed.pinned)
        assertEquals(MemoVisibility.PUBLIC, parsed.visibility)
        assertEquals("正文", parsed.content)
    }

    @Test
    fun offsetDateTimeAndEpochMillis_areBothAccepted() {
        val offset = MemoMarkdownCodec.decode(
            "---\nid: $id\nupdated: 2026-10-06T19:36:23+08:00\napp: moememos-md\n---\nx"
        )
        assertEquals(created, offset.updated)

        val epoch = MemoMarkdownCodec.decode(
            "---\nid: $id\nupdated: 1759750583000\napp: moememos-md\n---\nx"
        )
        assertEquals(Instant.ofEpochMilli(1759750583000L), epoch.updated)
    }

    @Test
    fun emptyBody_decodesToEmptyString() {
        val parsed = MemoMarkdownCodec.decode(
            "---\nid: $id\napp: moememos-md\n---\n"
        )
        assertEquals(id, parsed.id)
        assertEquals("", parsed.content)
    }

    @Test
    fun attachments_appearInFrontMatterAndNeverInTheBody() {
        val content = "语音闪念"
        val text = MemoMarkdownCodec.encode(
            id = id, content = content, created = created, updated = updated,
            pinned = false, archived = false, visibility = MemoVisibility.PRIVATE,
            attachments = listOf("voice-20261006-215812.m4a", "scan.jpg"),
        )

        assertTrue(text.contains("attachments: [\"voice-20261006-215812.m4a\", \"scan.jpg\"]"))
        // 正文必须一字不差：附件清单混进正文会被对账引擎当成外部编辑，反过来覆盖用户写的内容
        assertEquals(content, MemoMarkdownCodec.decode(text).content)
    }

    @Test
    fun attachmentsLineAddedExternally_isIgnoredNotImportedAsContent() {
        val parsed = MemoMarkdownCodec.decode(
            "---\nid: $id\napp: moememos-md\nattachments: [\"a.m4a\"]\n---\n正文"
        )
        assertEquals("正文", parsed.content)
    }

    @Test
    fun noAttachments_omitsTheKeyEntirely() {
        val text = MemoMarkdownCodec.encode(
            id = id, content = "x", created = created, updated = updated,
            pinned = false, archived = false, visibility = MemoVisibility.PRIVATE,
        )
        assertTrue(!text.contains("attachments:"))
    }
}
