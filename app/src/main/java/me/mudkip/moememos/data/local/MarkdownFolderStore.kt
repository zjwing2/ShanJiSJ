package me.mudkip.moememos.data.local

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import me.mudkip.moememos.data.local.entity.MemoEntity
import me.mudkip.moememos.data.local.entity.ResourceEntity
import me.mudkip.moememos.util.extractCustomTags
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * SAF 文件夹存储：每条 memo 一个 `.md` 文件，作为本地模式的“事实源”。
 *
 * 目录结构（用户通过 ACTION_OPEN_DOCUMENT_TREE 选择，例如 OneDrive 同步目录）：
 * ```
 * <root>/
 *   memos/2026-10/20261006-183623-3f9c2a1e.md
 *   attachments/3f9c2a1e/photo.jpg
 *   trash/2026-10/20261006-183623-3f9c2a1e.md
 * ```
 *
 * 两个刻意为之的决定：
 * - **删除 = 移入 `trash/`，绝不硬删**。OneDrive / FolderSync 双向同步下，硬删会立刻
 *   把云端也删掉，误操作无法挽回。
 * - **文件名只给人看，front matter 里的 `id` 才是主键**。用户在 Obsidian 里重命名文件
 *   不会丢笔记；文件名的 8 位后缀只是给扫描做免读取的快速索引。
 *
 * SAF 性能：`DocumentFile` 的每次查询都是一次跨进程调用。全量扫描时先 `listFiles()`
 * 一次拿完再逐个读流，绝不逐文件 `findFile()`。
 *
 * 本类无状态（绑定信息存在 [FolderMirrorPrefs]），因此 UI / 仓库包装层 / Worker
 * 各自 new 一个实例也不会读到不一致的目录。
 */
class MarkdownFolderStore(private val context: Context) {

    /** 文件夹里的一条笔记。 */
    data class Remote(
        val documentUri: Uri,
        val fileName: String,
        val lastModified: Long,
        val parsed: MemoMarkdownCodec.Parsed,
    )

    /** id 后 8 位 -> 文档 uri。仅凭文件名建立，扫描时不需要读取文件内容。 */
    private var indexCache: MutableMap<String, Uri>? = null

    fun isBound(): Boolean = FolderMirrorPrefs.isActive(context)

    // ---------------------------------------------------------------- 目录

    private fun root(): DocumentFile? {
        val uri = FolderMirrorPrefs.treeUri(context)?.toUri() ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
    }

    private fun childDir(parent: DocumentFile?, name: String, create: Boolean): DocumentFile? {
        if (parent == null) return null
        val existing = runCatching { parent.findFile(name) }.getOrNull()
        if (existing != null && existing.isDirectory) return existing
        if (!create) return null
        return runCatching { parent.createDirectory(name) }.getOrNull()
    }

    fun memosRoot(create: Boolean = false) = childDir(root(), "memos", create)

    fun trashRoot(create: Boolean = false) = childDir(root(), "trash", create)

    fun attachmentsRoot(create: Boolean = false) = childDir(root(), "attachments", create)

    private fun monthDir(parent: DocumentFile, yyyyMM: String, create: Boolean): DocumentFile? {
        val existing = runCatching { parent.findFile(yyyyMM) }.getOrNull()
        if (existing != null && existing.isDirectory) return existing
        if (!create) return null
        return runCatching { parent.createDirectory(yyyyMM) }.getOrNull()
    }

    /** `20261006-183623-3f9c2a1e.md` */
    fun fileNameFor(memo: MemoEntity): String {
        val t = memo.date.atZone(ZoneId.systemDefault())
        val stamp = String.format(
            Locale.US, "%04d%02d%02d-%02d%02d%02d",
            t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second
        )
        return "$stamp-${memo.identifier.take(8)}.md"
    }

    private fun monthOf(instant: Instant): String {
        val t = instant.atZone(ZoneId.systemDefault())
        return String.format(Locale.US, "%04d-%02d", t.year, t.monthValue)
    }

    // ---------------------------------------------------------------- 索引

    /**
     * 重建 id -> uri 索引。常规文件名只靠名字就能定位（不读内容），
     * 只有“不符合命名约定”的文件（用户在 Obsidian 里改过名、或收编进来的裸文件）
     * 才需要读一次 front matter 取 id。这类文件通常极少，代价可接受。
     */
    fun refreshIndex(): Map<String, Uri> {
        val built = LinkedHashMap<String, Uri>()
        val memos = memosRoot(create = false)
        if (memos != null) {
            for (month in scanDirs(memos)) {
                for (doc in mdFiles(month)) {
                    val name = doc.name ?: continue
                    val byName = keyOf(name)
                    if (byName != null) {
                        built[byName] = doc.uri
                        continue
                    }
                    val id = readText(doc.uri)
                        ?.let { MemoMarkdownCodec.decode(it).id }
                        ?: continue
                    if (id.length >= 8) built[id.take(8)] = doc.uri
                }
            }
        }
        indexCache = built
        return built
    }

    private fun index(): MutableMap<String, Uri> {
        indexCache?.let { return it }
        return refreshIndex().toMutableMap().also { indexCache = it }
    }

    private fun keyOf(fileName: String): String? {
        if (!fileName.endsWith(".md", ignoreCase = true)) return null
        val stem = fileName.dropLast(3)
        val key = stem.substringAfterLast('-', "")
        return key.takeIf { it.length >= 8 }
    }

    // ---------------------------------------------------------------- 写

    /**
     * 写入或更新一条 memo。
     *
     * @param targetUri 已知的目标文件（外部裸文件收编时复用原文件，避免产生重复文件）。
     * @return 是否写入成功。失败不抛异常——文件夹同步是增量能力，绝不能因为它让 App 崩掉。
     */
    fun writeMemo(
        memo: MemoEntity,
        resources: List<ResourceEntity> = emptyList(),
        targetUri: Uri? = null,
    ): Boolean {
        val root = memosRoot(create = true) ?: return false
        val month = monthDir(root, monthOf(memo.date), create = true) ?: return false

        val known = targetUri ?: index()[memo.identifier.take(8)]
        val target = known?.let { uri ->
            DocumentFile.fromSingleUri(context, uri)?.takeIf { it.isFile }
        } ?: runCatching {
            month.createFile("text/markdown", fileNameFor(memo))
        }.getOrNull() ?: return false

        val text = MemoMarkdownCodec.encode(
            id = memo.identifier,
            content = memo.content,
            created = memo.date,
            updated = memo.lastModified,
            pinned = memo.pinned,
            archived = memo.archived,
            visibility = memo.visibility,
            tags = extractCustomTags(memo.content).sorted(),
            attachments = resources.map { it.filename }.filter { it.isNotBlank() },
        )
        if (!writeText(target.uri, text)) return false

        indexCache?.put(memo.identifier.take(8), target.uri)
        writeAttachments(memo.identifier, resources)
        return true
    }

    /** 删除 = 复制进 `trash/` 再删原文件。 */
    fun trashMemo(memo: MemoEntity): Boolean {
        val uri = index()[memo.identifier.take(8)] ?: return false
        val source = DocumentFile.fromSingleUri(context, uri) ?: return false
        if (!source.isFile) return false
        val name = source.name ?: fileNameFor(memo)

        val text = readText(uri)
        if (text != null) {
            val tMonth = trashRoot(create = true)?.let { monthDir(it, monthOf(memo.date), create = true) }
            if (tMonth != null) {
                runCatching {
                    tMonth.createFile("text/markdown", name)?.let { writeText(it.uri, text) }
                }
            }
        }

        val deleted = runCatching { source.delete() }.getOrDefault(false)
        if (deleted) indexCache?.remove(memo.identifier.take(8))
        return deleted
    }

    private fun writeAttachments(memoId: String, resources: List<ResourceEntity>) {
        if (resources.isEmpty()) return
        val base = childDir(attachmentsRoot(create = true), memoId.take(8), create = true) ?: return
        for (resource in resources) {
            val name = resource.filename
            if (name.isBlank()) continue
            if (runCatching { base.findFile(name) }.getOrNull() != null) continue
            val dest = runCatching {
                base.createFile(resource.mimeType ?: "application/octet-stream", name)
            }.getOrNull() ?: continue
            val source = (resource.localUri ?: resource.uri).toUri()
            runCatching {
                context.contentResolver.openInputStream(source)?.use { input ->
                    context.contentResolver.openOutputStream(dest.uri)?.use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    /**
     * 附件随笔记一起进回收区：`attachments/<id 前 8 位>/` -> `trash/attachments/<id 前 8 位>/`。
     *
     * SAF 没有 move，只能递归复制再删源。不做的话，删掉的笔记（比如一段语音）会永远
     * 留在同步目录里被 OneDrive 反复搬运。
     */
    fun trashAttachments(memoId: String): Boolean {
        if (memoId.length < 8) return false
        val source = childDir(attachmentsRoot(create = false), memoId.take(8), create = false)
            ?: return false
        val trashAttachments = childDir(trashRoot(create = true), "attachments", create = true)
            ?: return false
        val target = childDir(trashAttachments, memoId.take(8), create = true) ?: return false
        copyInto(source, target)
        return runCatching { source.delete() }.getOrDefault(false)
    }

    /**
     * 清空回收站：删除 `trash/` 下的全部内容（含月份子目录与 `trash/attachments/`）。
     * 与删除笔记相反，这里做**硬删**——回收站本身就是"已删除"的兜底，用户主动点
     * "清空"即表示放弃这些文件。返回成功删除的条目数。
     */
    fun emptyTrash(): Int {
        val trash = trashRoot(create = false) ?: return 0
        if (!trash.isDirectory) return 0
        val children = runCatching { trash.listFiles() }.getOrNull() ?: return 0
        var count = 0
        for (child in children) {
            if (deleteRecursive(child)) count++
        }
        return count
    }

    /** SAF 没有递归删除，只能自顶向下逐层删。 */
    private fun deleteRecursive(file: DocumentFile): Boolean {
        if (file.isDirectory) {
            val children = runCatching { file.listFiles() }.getOrNull() ?: emptyArray()
            for (child in children) deleteRecursive(child)
        }
        return runCatching { file.delete() }.getOrDefault(false)
    }

    private fun copyInto(source: DocumentFile, target: DocumentFile) {
        val children = runCatching { source.listFiles() }.getOrNull() ?: return
        for (child in children) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                val sub = childDir(target, name, create = true) ?: continue
                copyInto(child, sub)
                continue
            }
            val dest = runCatching { target.findFile(name) }.getOrNull()
                ?: runCatching {
                    target.createFile(child.type ?: "application/octet-stream", name)
                }.getOrNull()
                ?: continue
            runCatching {
                context.contentResolver.openInputStream(child.uri)?.use { input ->
                    context.contentResolver.openOutputStream(dest.uri)?.use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun writeText(uri: Uri, text: String): Boolean {
        return try {
            val stream = context.contentResolver.openOutputStream(uri, "wt") ?: return false
            stream.use {
                it.write(text.toByteArray(Charsets.UTF_8))
                it.flush()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun readText(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }.getOrNull()

    // ---------------------------------------------------------------- 读

    /** 全量扫描 `memos` 目录树（含各月份子目录），返回带 id 与不带 id 的两种文件。 */
    fun scan(): List<Remote> {
        val memos = memosRoot(create = false) ?: return emptyList()
        val out = ArrayList<Remote>()
        for (dir in scanDirs(memos)) {
            for (doc in mdFiles(dir)) {
                val name = doc.name ?: continue
                val text = readText(doc.uri) ?: continue
                out.add(
                    Remote(
                        documentUri = doc.uri,
                        fileName = name,
                        lastModified = runCatching { doc.lastModified() }.getOrDefault(0L),
                        parsed = MemoMarkdownCodec.decode(text),
                    )
                )
            }
        }
        return out
    }

    /** `memos/` 自身 + 其下的月份子目录。 */
    private fun scanDirs(memos: DocumentFile): List<DocumentFile> {
        val dirs = ArrayList<DocumentFile>()
        dirs.add(memos)
        runCatching { memos.listFiles() }.getOrNull()
            ?.filter { it.isDirectory }
            ?.let { dirs.addAll(it) }
        return dirs
    }

    private fun mdFiles(dir: DocumentFile): List<DocumentFile> =
        runCatching { dir.listFiles() }.getOrNull()
            ?.filter { it.isFile && (it.name?.endsWith(".md", ignoreCase = true) == true) }
            ?: emptyList()
}
