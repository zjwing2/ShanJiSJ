package me.mudkip.moememos.util

import me.mudkip.moememos.data.local.entity.MemoEntity
import me.mudkip.moememos.data.model.MemoVisibility
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MemoSearchTest {

    private fun memo(content: String) = MemoEntity(
        identifier = "test-id",
        accountKey = "local",
        content = content,
        date = Instant.EPOCH,
        visibility = MemoVisibility.PRIVATE,
        pinned = false
    )

    @Test
    fun matchesContentSubstring() {
        val m = memo("今天去爬山，看到了美丽的风景")
        assertTrue(m.matchesQuery("爬山"))
        assertTrue(m.matchesQuery("风景"))
    }

    @Test
    fun matchesTitleHeading() {
        val m = memo("##### 我的旅行计划\n明天去海边散步")
        assertTrue(m.matchesQuery("旅行计划"))
        assertTrue(m.matchesQuery("海边"))
    }

    @Test
    fun matchesCustomTagKeyword() {
        val m = memo("学习了 #Kotlin 协程用法")
        assertTrue(m.matchesQuery("Kotlin"))
        assertTrue(m.matchesQuery("kotlin")) // 大小写不敏感
    }

    @Test
    fun ignoresCaseForLatin() {
        val m = memo("Weekly issue about PPT")
        assertTrue(m.matchesQuery("ppt"))
        assertTrue(m.matchesQuery("WEEKLY"))
    }

    @Test
    fun emptyQueryMatchesAll() {
        val m = memo("任意内容")
        assertTrue(m.matchesQuery(""))
        assertTrue(m.matchesQuery("   "))
    }

    @Test
    fun noMatchReturnsFalse() {
        val m = memo("这是一条完全无关的笔记")
        assertFalse(m.matchesQuery("不存在的词xyz"))
    }
}
