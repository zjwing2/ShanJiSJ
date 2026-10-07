package me.mudkip.moememos.data.repository

import android.net.Uri
import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.getOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import me.mudkip.moememos.data.local.entity.MemoEntity
import me.mudkip.moememos.data.local.entity.ResourceEntity
import me.mudkip.moememos.data.model.MemoVisibility
import me.mudkip.moememos.data.model.SyncStatus
import me.mudkip.moememos.data.model.User
import okhttp3.MediaType

/**
 * [LocalDatabaseRepository] 的“写穿”装饰层：所有读写照旧委托给 Room，
 * 只在写操作成功后额外把变化落到 Markdown 文件夹。
 *
 * 这样做的好处是 UI 一行都不用改——分页、Flow 观察、全文搜索仍然全部走 Room；
 * 文件夹只是多出来的一份可读、可同步、可被外部编辑器修改的副本。
 *
 * 未绑定文件夹时本层是纯透传（[FolderMirrorEngine] 内部会短路），
 * 因此可以无条件包装，用户之后在设置里打开开关立刻生效，无需重启。
 */
class FolderMirrorRepository(
    private val delegate: LocalDatabaseRepository,
    private val engine: FolderMirrorEngine,
) : AbstractMemoRepository() {

    override val syncStatus: StateFlow<SyncStatus> get() = delegate.syncStatus

    override fun observeMemos(): Flow<List<MemoEntity>> = delegate.observeMemos()

    override suspend fun listMemos(): ApiResponse<List<MemoEntity>> = delegate.listMemos()

    override suspend fun listArchivedMemos(): ApiResponse<List<MemoEntity>> = delegate.listArchivedMemos()

    override suspend fun getMemo(identifier: String): MemoEntity? = delegate.getMemo(identifier)

    override suspend fun createMemo(
        content: String,
        visibility: MemoVisibility,
        resources: List<ResourceEntity>,
        tags: List<String>?,
        deferPush: Boolean,
    ): ApiResponse<MemoEntity> {
        val result = delegate.createMemo(content, visibility, resources, tags, deferPush)
        result.getOrNull()?.let { engine.push(it.identifier) }
        return result
    }

    override suspend fun updateMemo(
        identifier: String,
        content: String?,
        resources: List<ResourceEntity>?,
        visibility: MemoVisibility?,
        tags: List<String>?,
        pinned: Boolean?,
        deferPush: Boolean,
    ): ApiResponse<MemoEntity> {
        val result = delegate.updateMemo(
            identifier, content, resources, visibility, tags, pinned, deferPush
        )
        result.getOrNull()?.let { engine.push(it.identifier) }
        return result
    }

    override suspend fun deleteMemo(identifier: String): ApiResponse<Unit> {
        // 先取快照：删除之后就拿不到 date / 文件名了
        val snapshot = delegate.getMemo(identifier)
        val result = delegate.deleteMemo(identifier)
        if (result.getOrNull() != null && snapshot != null) {
            engine.trash(snapshot)
        }
        return result
    }

    override suspend fun archiveMemo(identifier: String): ApiResponse<Unit> {
        val result = delegate.archiveMemo(identifier)
        if (result.getOrNull() != null) engine.push(identifier)
        return result
    }

    override suspend fun restoreMemo(identifier: String): ApiResponse<Unit> {
        val result = delegate.restoreMemo(identifier)
        if (result.getOrNull() != null) engine.push(identifier)
        return result
    }

    override suspend fun listTags(): ApiResponse<List<String>> = delegate.listTags()

    override suspend fun listResources(): ApiResponse<List<ResourceEntity>> = delegate.listResources()

    override suspend fun createResource(
        filename: String,
        type: MediaType?,
        contentUri: Uri,
        memoIdentifier: String?,
    ): ApiResponse<ResourceEntity> {
        val result = delegate.createResource(filename, type, contentUri, memoIdentifier)
        memoIdentifier?.let { engine.push(it) }
        return result
    }

    override suspend fun deleteResource(identifier: String): ApiResponse<Unit> =
        delegate.deleteResource(identifier)

    override suspend fun getCurrentUser(): ApiResponse<User> = delegate.getCurrentUser()

    override suspend fun cacheResourceFile(identifier: String, downloadedUri: Uri): ApiResponse<Unit> =
        delegate.cacheResourceFile(identifier, downloadedUri)

    override suspend fun sync(): ApiResponse<Unit> = delegate.sync()

    override suspend fun flushPendingPush(identifier: String) = delegate.flushPendingPush(identifier)

    override fun close() = delegate.close()
}
