package me.mudkip.moememos.data.transcription

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 端侧模型的落地与生命周期。
 *
 * 负责三件事：模型放在哪、下没下全、怎么下。它是[TranscriptionModelCatalog]中
 * 那份清单的唯一执行者，别处只读状态、不碰文件。
 *
 * ## 为什么模型不进 APK
 *
 * 三个文件合计约 228.8 MB。塞进安装包会让 APK 从 60 MB 涨到 290 MB，
 * 且对「用云端转写」或「完全不转写」的用户纯属浪费。所以改成按需下载，
 * 下载前界面会明确写出体积。
 *
 * ## 「已就绪」怎么判定
 *
 * 只看文件大小**不够**：大小对得上、内容却是坏的情况无法排除，而一个坏掉的
 * 228 MB 模型会在 ONNX Runtime 里以极难看懂的方式报错。所以下载完成时会算一次
 * SHA-256 并写入 `manifest.txt` 存证；[isReady] 同时要求「大小对 + 存证对」。
 * 反过来，每次启动都重算 228 MB 的哈希又是几秒的浪费，因此只在校验一次后记账。
 */
@Singleton
class TranscriptionModelManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /** 模型下载与就绪状态。 */
    sealed interface State {

        /** 一个文件都没下（或下到一半被打断）。 */
        data object NotDownloaded : State

        /** 正在下载。[fraction] 是全部文件的总体进度。 */
        data class Downloading(
            val fileName: String,
            val fileIndex: Int,
            val fileCount: Int,
            val bytesDone: Long,
            val totalBytes: Long,
        ) : State {
            val fraction: Float
                get() = if (totalBytes <= 0L) 0f else (bytesDone.toFloat() / totalBytes).coerceIn(0f, 1f)
        }

        /** 三个文件齐全且校验通过。 */
        data object Ready : State

        /** 下载失败，[message] 可直接展示。 */
        data class Failed(val message: String) : State
    }

    private val modelDir: File = File(context.filesDir, "sherpa-models")

    /** 记录「这一组文件的哈希已经验证过」，避免每次启动重算 228 MB。 */
    private val manifestFile: File = File(modelDir, MANIFEST_NAME)

    private val _state = MutableStateFlow<State>(if (isReady()) State.Ready else State.NotDownloaded)
    val state: StateFlow<State> = _state.asStateFlow()

    // 下 228 MB 单文件会远超默认读超时，专用客户端：不设读超时，靠分块循环
    // 里的取消检查来响应「用户点了取消」，而不是靠 socket 超时。
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** 单个模型文件的绝对路径。文件可能还不存在。 */
    fun fileOf(model: RemoteModelFile): File = File(modelDir, model.fileName)

    /** 模型目录，用于界面展示占用位置。 */
    fun directory(): File = modelDir

    /**
     * 是否三个文件齐全。只做 `stat` 级别检查，可以在主线程调用。
     */
    fun isReady(): Boolean {
        if (!manifestMatches()) return false
        return SherpaModelCatalog.files.all { model ->
            val f = fileOf(model)
            f.isFile && f.length() == model.sizeBytes
        }
    }

    /** 当前已占用的磁盘字节数（含未完成的临时文件），用于设置页展示。 */
    fun usedBytes(): Long =
        SherpaModelCatalog.files.sumOf { model ->
            fileOf(model).takeIf { it.isFile }?.length() ?: 0L
        } + SherpaModelCatalog.files.sumOf { model ->
            File(modelDir, "${model.fileName}$PART_SUFFIX").takeIf { it.isFile }?.length() ?: 0L
        }

    /**
     * 下载全部缺失的文件。
     *
     * 幂等且可续传：已下好且校验通过的文件直接跳过；下到一半的 `.part`
     * 会带着已有的字节数继续（前提是服务端支持 Range，三个源都支持）。
     * 单个源失败自动切换下一个，已下载的进度不丢。
     *
     * @return true 表示全部就绪。失败或取消时状态里已写明原因。
     */
    suspend fun download(): Boolean = withContext(Dispatchers.IO) {
        val total = SherpaModelCatalog.totalBytes
        // 已经下好的部分先计入进度，这样续传时进度条从正确的位置开始
        var doneBytes = SherpaModelCatalog.files.sumOf { model ->
            fileOf(model).takeIf { it.isFile && it.length() == model.sizeBytes }?.length() ?: 0L
        }
        val files = SherpaModelCatalog.files
        _state.value = State.Downloading(
            fileName = files.first().fileName,
            fileIndex = 0,
            fileCount = files.size,
            bytesDone = doneBytes,
            totalBytes = total,
        )

        try {
            files.forEachIndexed { index, model ->
                if (!modelDir.exists() && !modelDir.mkdirs()) {
                    throw IOException("无法创建模型目录：${modelDir.absolutePath}")
                }

                val target = fileOf(model)
                if (target.isFile && target.length() == model.sizeBytes) {
                    return@forEachIndexed // 已下好，跳过
                }

                val indexLabel = index + 1
                val baseBefore = doneBytes
                fetch(model) { bytesWritten ->
                    _state.value = State.Downloading(
                        fileName = model.fileName,
                        fileIndex = indexLabel,
                        fileCount = files.size,
                        bytesDone = baseBefore + bytesWritten,
                        totalBytes = total,
                    )
                }
                doneBytes = baseBefore + model.sizeBytes
            }

            writeManifest()
            _state.value = State.Ready
            true
        } catch (e: CancellationException) {
            // 用户主动取消：保留 .part，下次接着下，不算失败
            _state.value = State.NotDownloaded
            throw e
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: e::class.java.simpleName)
            false
        }
    }

    /** 删除全部模型文件（含未完成的临时文件），回到未下载状态。 */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        SherpaModelCatalog.files.forEach { model ->
            runCatching { fileOf(model).delete() }
            runCatching { File(modelDir, "${model.fileName}$PART_SUFFIX").delete() }
        }
        runCatching { manifestFile.delete() }
        _state.value = State.NotDownloaded
    }

    /** 外部改动过文件（比如用户在设置里清了数据）后，重新对齐一次状态。 */
    fun refresh() {
        if (_state.value is State.Downloading) return
        _state.value = if (isReady()) State.Ready else State.NotDownloaded
    }

    // ------------------------------------------------------------------ 下载实现

    /**
     * 取一个文件：依次试各个镜像，成功后校验哈希再改名落地。
     *
     * @param onProgress 已写入的字节数（含续传部分），回调可能是高频的。
     */
    private suspend fun fetch(model: RemoteModelFile, onProgress: suspend (Long) -> Unit) {
        val target = fileOf(model)
        val partial = File(modelDir, "${model.fileName}$PART_SUFFIX")

        var lastError: Exception? = null
        for (url in model.urls) {
            currentCoroutineContext().ensureActive()
            try {
                streamTo(url, model.sizeBytes, partial, onProgress)
                lastError = null
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 换下一个源。已落盘的 .part 保留，下个源接着续传。
                lastError = e
            }
        }

        val failure = lastError
        if (failure != null) {
            throw IOException("下载 ${model.fileName} 失败：${failure.message}", failure)
        }

        // 只有校验通过才认：坏掉的 228 MB 模型比没有模型更难排查。
        if (partial.length() != model.sizeBytes) {
            throw IOException(
                "下载不完整：${model.fileName} 实际 ${partial.length()} 字节，应为 ${model.sizeBytes} 字节"
            )
        }
        val digest = partial.sha256()
        if (!digest.equals(model.sha256, ignoreCase = true)) {
            partial.delete()
            throw IOException("${model.fileName} 校验不通过，已删除，请重试")
        }

        if (!partial.renameTo(target)) {
            // 某些文件系统上 rename 到已存在的路径会失败，先删再改
            target.delete()
            if (!partial.renameTo(target)) {
                throw IOException("无法写入 ${target.absolutePath}")
            }
        }
        onProgress(model.sizeBytes)
    }

    /** 从单一下载源拉取，支持续传。 */
    private suspend fun streamTo(
        url: String,
        expectedSize: Long,
        partial: File,
        onProgress: suspend (Long) -> Unit,
    ) {
        val alreadyOnDisk = partial.takeIf { it.isFile }?.length() ?: 0L

        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        if (alreadyOnDisk in 1 until expectedSize) {
            builder.header("Range", "bytes=$alreadyOnDisk-")
        }
        val request = builder.build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body

            // 只有 206 才代表服务端接受了 Range。若返回 200，说明它忽略了 Range，
            // 那就必须从头写，否则新旧字节会拼出一份必然校验失败的垃圾。
            val resuming = response.code == 206 && alreadyOnDisk > 0L
            val startFrom = if (resuming) alreadyOnDisk else 0L

            // 完整性校验统一放在 fetch() 里对落盘文件整份重算，这里只负责把字节写对。
            java.io.FileOutputStream(partial, resuming).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = startFrom
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        written += read
                        onProgress(written.coerceAtMost(expectedSize))
                    }
                    out.flush()
                }
            }

            // 超过预期说明这个源给的不是我们要的文件，留着只会污染续传进度。
            if (partial.length() > expectedSize) {
                partial.delete()
                throw IOException("返回内容超过预期大小，可能不是模型文件")
            }
        }
    }

    // ------------------------------------------------------------------ 存证

    /** 存证内容与当前清单是否一致。清单变了（换了模型版本）就不算就绪。 */
    private fun manifestMatches(): Boolean {
        if (!manifestFile.isFile) return false
        val recorded = runCatching { manifestFile.readText() }.getOrNull() ?: return false
        val expected = SherpaModelCatalog.files.joinToString("\n") { "${it.fileName} ${it.sha256}" }
        return recorded.trim() == expected.trim()
    }

    private fun writeManifest() {
        runCatching {
            if (!modelDir.exists()) modelDir.mkdirs()
            manifestFile.writeText(
                SherpaModelCatalog.files.joinToString("\n") { "${it.fileName} ${it.sha256}" }
            )
        }
    }

    /** 整个文件的 SHA-256，十六进制小写。 */
    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MANIFEST_NAME = "manifest.txt"
        const val PART_SUFFIX = ".part"
        const val BUFFER_SIZE = 256 * 1024
        const val USER_AGENT = "MoeMemos-MD/2.0.5 (Android)"
    }
}
