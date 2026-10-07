package me.mudkip.moememos.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.mudkip.moememos.data.local.MarkdownFolderStore
import me.mudkip.moememos.data.local.dao.MemoDao
import me.mudkip.moememos.data.local.entity.MemoEntity
import me.mudkip.moememos.data.model.Account
import java.time.Instant
import java.util.UUID

/**
 * 文件夹与 Room 之间的双向对账引擎。
 *
 * 数据流：**Room 当索引，Markdown 文件夹当事实源**。
 * - 增改删时由 [FolderMirrorRepository] 立刻写穿到文件夹（低延迟）；
 * - 周期性对账（[FolderMirrorWorker]，15 分钟）负责把外部编辑（Obsidian / VS Code）
 *   收回来，并补齐任何遗漏的推送。
 *
 * ## 冲突判定
 *
 * 每个文件都有两个时间信号：front matter 里的 `updated`（本 App 写的声明时间）与文件
 * 系统的 mtime（任何写入都会动）。判定规则：
 *
 * 1. **内容与元数据都没变 → 什么都不做**。这一条是所有收敛性的基础，它保证本 App
 *    自己写出去的文件不会被自己再读回来折腾一遍。
 * 2. front matter 的 `updated` 比本地 `lastModified` 新 → 远端更新，拉取。
 * 3. 内容变了但 `updated` 没变（Obsidian 改正文不会动 front matter），且 mtime 新于本地、
 *    同时 `updated` 又不比本地旧 → 认定为外部编辑，拉取。这里刻意加上“`updated` 不比本地旧”
 *    的条件：OneDrive 下载旧版本会把 mtime 刷成当前时间，若单看 mtime 就会用旧内容
 *    覆盖用户刚在 App 里的新编辑。
 * 4. 其余情况 → 本地赢，推送。
 *
 * ## 收敛性
 *
 * 因为规则 1 以“内容是否相同”为闸门，任何一次写入之后双方内容都会一致，
 * 下一轮不会再触发写入，不会出现来回覆盖的死循环。
 */
class FolderMirrorEngine(
    private val memoDao: MemoDao,
    private val store: MarkdownFolderStore,
) {
    /** 文件夹同步只服务本地账号。 */
    private val accountKey = Account.Local().accountKey()

    /**
     * 写穿 / 对账互斥。仓库包装层（前台，用户刚点了保存）和 Worker（后台）可能同时
     * 触发读写，必须串行化，否则会出现“刚写好的文件被对账当成外部变更读回来”。
     */
    private val mutex = Mutex()

    /** 单条写穿：把某条 memo 立刻落成文件。失败静默——文件夹同步是增量能力，不该影响主流程。 */
    suspend fun push(identifier: String) = mutex.withLock {
        if (!store.isBound()) return@withLock
        val memo = memoDao.getMemoById(identifier, accountKey) ?: return@withLock
        if (memo.isDeleted) return@withLock
        runCatching {
            store.writeMemo(memo, memoDao.getMemoResources(identifier, accountKey))
        }
    }

    /** 删除：移入 `trash/`，绝不硬删。附件（图片、录音）跟着笔记一起走。 */
    suspend fun trash(memo: MemoEntity) = mutex.withLock {
        if (!store.isBound()) return@withLock
        runCatching {
            store.trashMemo(memo)
            store.trashAttachments(memo.identifier)
        }
    }

    /** 全量对账。返回变更条数，仅供日志/提示使用。 */
    suspend fun reconcile(): Int = mutex.withLock {
        if (!store.isBound()) return@withLock 0

        store.refreshIndex()
        val remotes = store.scan()
        var changes = 0

        val remoteById = HashMap<String, MarkdownFolderStore.Remote>(remotes.size)
        for (remote in remotes) {
            remote.parsed.id?.let { remoteById[it] = remote }
        }

        // ---------- 1. 拉取：文件夹 -> Room ----------
        val known = memoDao.getAllMemosForSync(accountKey)
            .filterNot { it.isDeleted }
            .associateBy { it.identifier }

        for (remote in remotes) {
            val id = remote.parsed.id
            if (id == null) {
                // 裸文件（外部随手新建的 .md）：收编为新笔记，并把 front matter 写回同一个文件
                val memo = newMemoFrom(remote, UUID.randomUUID().toString())
                memoDao.insertMemo(memo)
                // front matter 写回**同一个文件**（而不是新建），否则下一轮又会被当成新文件重复导入
                val adopted = runCatching {
                    store.writeMemo(memo, emptyList(), targetUri = remote.documentUri)
                }.getOrDefault(false)
                if (adopted) {
                    // 这一步很关键：把刚收编的文件登记进索引，否则下面第 2 步会再写出一份重复文件
                    remoteById[memo.identifier] = remote
                    changes++
                }
                continue
            }
            val local = known[id]
            if (local == null) {
                memoDao.insertMemo(newMemoFrom(remote, id))
                changes++
                continue
            }
            val merged = mergeFromRemote(local, remote)
            if (merged != null) {
                memoDao.insertMemo(merged)
                changes++
            }
        }

        // ---------- 2. 推送：Room -> 文件夹 ----------
        val fresh = memoDao.getAllMemosForSync(accountKey).filterNot { it.isDeleted }
        for (memo in fresh) {
            val remote = remoteById[memo.identifier]
            if (remote == null) {
                // 文件夹里还没有这条（首次启用时的全量导出也走这里）
                if (store.writeMemo(memo, memoDao.getMemoResources(memo.identifier, accountKey))) changes++
                continue
            }
            val contentDiffers = !contentEquals(memo, remote)
            val metaDiffers = metaDiffers(memo, remote)
            val declared = declaredTime(remote)
            val newerLocally = memo.lastModified.isAfter(declared.plusSeconds(SLACK_SECONDS))
            if (!contentDiffers && !metaDiffers && !newerLocally) continue
            val ok = store.writeMemo(
                memo,
                memoDao.getMemoResources(memo.identifier, accountKey),
                targetUri = remote.documentUri,
            )
            if (ok) changes++
        }

        changes
    }

    // ------------------------------------------------------------------ 判定

    /** 远端“声明的”更新时间：优先 front matter，缺失时退回文件 mtime。 */
    private fun declaredTime(remote: MarkdownFolderStore.Remote): Instant {
        remote.parsed.updated?.let { return it }
        val mtime = Instant.ofEpochMilli(remote.lastModified)
        return if (mtime > Instant.EPOCH) mtime else Instant.EPOCH
    }

    private fun mtimeOf(remote: MarkdownFolderStore.Remote): Instant =
        Instant.ofEpochMilli(remote.lastModified)

    private fun contentEquals(memo: MemoEntity, remote: MarkdownFolderStore.Remote): Boolean =
        memo.content.trim() == remote.parsed.content.trim()

    private fun metaDiffers(memo: MemoEntity, remote: MarkdownFolderStore.Remote): Boolean =
        memo.pinned != remote.parsed.pinned ||
            memo.archived != remote.parsed.archived ||
            memo.visibility != remote.parsed.visibility

    /**
     * 返回需要写回 Room 的新实体；返回 null 表示远端不比本地新或没有实质差异。
     */
    private fun mergeFromRemote(
        local: MemoEntity,
        remote: MarkdownFolderStore.Remote,
    ): MemoEntity? {
        val contentDiffers = !contentEquals(local, remote)
        val metaDiffers = metaDiffers(local, remote)
        if (!contentDiffers && !metaDiffers) return null

        val flag = local.lastModified.plusSeconds(SLACK_SECONDS)
        val declared = declaredTime(remote)
        val mtime = mtimeOf(remote)
        val stamp = remote.parsed.updated

        val remoteNewer = if (stamp != null) {
            // 正规路径：front matter 声明更新了
            declared.isAfter(flag) ||
                // 外部编辑器改了正文但没动 front matter
                (contentDiffers && mtime.isAfter(flag) && !stamp.isBefore(local.lastModified))
        } else {
            mtime.isAfter(flag)
        }
        if (!remoteNewer) return null

        val newLastModified = if (declared.isAfter(mtime)) declared else mtime
        // 元数据以远端为准（用户可能就是在文件里改了 pinned / archived / visibility）
        val merged = local.copy(
            content = remote.parsed.content,
            pinned = remote.parsed.pinned,
            archived = remote.parsed.archived,
            visibility = remote.parsed.visibility,
            lastModified = newLastModified,
            lastSyncedAt = null,
        )
        return if (merged == local) null else merged
    }

    private fun newMemoFrom(remote: MarkdownFolderStore.Remote, identifier: String): MemoEntity {
        val mtime = mtimeOf(remote)
        val created = remote.parsed.created
            ?: mtime.takeIf { it > Instant.EPOCH }
            ?: Instant.now()
        return MemoEntity(
            identifier = identifier,
            accountKey = accountKey,
            content = remote.parsed.content,
            date = created,
            visibility = remote.parsed.visibility,
            pinned = remote.parsed.pinned,
            archived = remote.parsed.archived,
            needsSync = false,
            isDeleted = false,
            lastModified = declaredTime(remote).takeIf { it > Instant.EPOCH } ?: created,
            lastSyncedAt = null,
        )
    }

    private companion object {
        /**
         * 时间戳比较的宽容窗口。SAF / 同步盘的时间戳精度与写入延迟都可能带来几秒误差，
         * 宁可少判几次“谁更新”，也不要因为毫秒级抖动反复互写。
         */
        const val SLACK_SECONDS = 3L
    }
}
