package me.mudkip.moememos.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skydoves.sandwich.ApiResponse
import com.skydoves.sandwich.suspendOnSuccess
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.mudkip.moememos.R
import me.mudkip.moememos.data.local.VoiceRecorder
import me.mudkip.moememos.data.local.entity.MemoEntity
import me.mudkip.moememos.data.local.entity.ResourceEntity
import me.mudkip.moememos.data.model.MemoVisibility
import me.mudkip.moememos.data.service.MemoService
import me.mudkip.moememos.data.transcription.TranscriptionManager
import me.mudkip.moememos.data.transcription.TranscriptionResult
import me.mudkip.moememos.data.web.WebFetchError
import me.mudkip.moememos.data.web.WebFetchResult
import me.mudkip.moememos.data.web.WebPageFetcher
import me.mudkip.moememos.data.web.WebLinkAppend
import me.mudkip.moememos.data.web.buildWebPageBlock
import me.mudkip.moememos.ext.settingsDataStore
import me.mudkip.moememos.widget.WidgetUpdater
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject

/**
 * 录音转写的界面状态。
 *
 * 刻意只有三态：转写是「要么在跑、要么成了、要么失败了」的短过程，
 * 没有中间态可展示，也不需要在界面上排队。
 */
sealed interface TranscriptionState {

    /** 没有在转写。 */
    data object Idle : TranscriptionState

    /** 正在转写。端侧首次加载模型时会有几秒可感知的等待。 */
    data object Busy : TranscriptionState

    /** 失败，[message] 可直接展示，并提供重试。 */
    data class Failed(val message: String) : TranscriptionState
}

/**
 * 网页读取的界面状态。
 *
 * 同样是三态。注意 [Busy] 里带着地址：用户可能在等待期间又改了正文，
 * 重试要重试的必须是**当时那一个**地址，而不是正文里现在碰巧出现的那个。
 */
sealed interface LinkFetchState {

    /** 没有在读取。 */
    data object Idle : LinkFetchState

    /** 正在读取 [url]。 */
    data class Busy(val url: String) : LinkFetchState

    /** 失败，[message] 可直接展示，并提供重试。 */
    data class Failed(val message: String, val url: String) : LinkFetchState
}

@HiltViewModel
class MemoInputViewModel @Inject constructor(
    application: Application,
    private val memoService: MemoService,
    private val transcription: TranscriptionManager,
    private val webPageFetcher: WebPageFetcher,
) : AndroidViewModel(application) {
    private val context: Application get() = getApplication()
    val draft = context.settingsDataStore.data.map { settings ->
        settings.usersList.firstOrNull { it.accountKey == settings.currentUser }?.settings?.draft
    }
    val autosaveEnabled = context.settingsDataStore.data.map { settings ->
        settings.usersList.firstOrNull { it.accountKey == settings.currentUser }?.settings?.autosave ?: false
    }
    var uploadResources = mutableStateListOf<ResourceEntity>()

    /**
     * 录音机随 ViewModel 存活：录音过程中转屏、短暂切后台都不会中断，
     * ViewModel 销毁（离开输入页）时兜底丢弃未保存的录音。
     */
    val voiceRecorder = VoiceRecorder(getApplication())

    // ------------------------------------------------------------------ 语音转写

    /** 当前转写状态，供输入页显示「转写中 / 失败 + 重试」。 */
    var transcriptionState by mutableStateOf<TranscriptionState>(TranscriptionState.Idle)
        private set

    /**
     * 转写完成、等待写进正文的段落。
     *
     * 用 Channel 而不是 StateFlow：这是**一次性事件**，不是状态。用 StateFlow 的话
     * 每次重组都会重新投递同一段文字，正文会被追加两遍。
     *
     * 正文的真身在输入页的 `text` 里（页面级 `rememberSaveable`），ViewModel 拿不到，
     * 所以只能把结果推过去由页面落笔。
     */
    private val appendChannel = Channel<String>(Channel.BUFFERED)
    val appendEvents: Flow<String> = appendChannel.receiveAsFlow()

    /** 最近一次录下的音频。转写失败后「重试」要有个明确的对象。 */
    private var lastRecording: ResourceEntity? = null

    /** 正在跑的转写任务，供「提交/退出前等它收尾」使用。 */
    private var transcriptionJob: Job? = null

    /** 是否有可重试的录音。 */
    val canRetryTranscription: Boolean
        get() = lastRecording != null && transcriptionState is TranscriptionState.Failed

    /** 开始录制。返回 false 表示麦克风不可用（通常被其他应用占用）。 */
    fun startRecording(): Boolean = voiceRecorder.start() != null

    fun cancelRecording() = voiceRecorder.discard()

    /**
     * 结束录制并落库。
     *
     * 返回 null 有两种含义，调用方都按「没有录音产生」处理：录得太短（< 800ms，
     * 编码器还没吐出数据），或启动就失败。上传失败会返回 Failure，正常走错误提示。
     *
     * 落库成功后会顺带触发一次转写（如果用户开启了转写）。放在这里而不是调用方，
     * 是为了让「点完成」和「在录音中直接点发送」两条路径都覆盖到，不用各写一遍。
     */
    suspend fun finishRecording(memoIdentifier: String?): ApiResponse<ResourceEntity>? {
        val file = voiceRecorder.finish() ?: return null
        return try {
            val response = uploadRecording(file, memoIdentifier)
            response.suspendOnSuccess { transcribeRecording(data) }
            response
        } finally {
            runCatching { file.delete() }
        }
    }

    /**
     * 对一条已落库的音频资源发起转写。
     *
     * 转写跑在 [viewModelScope] 上而不是页面的 coroutineScope 上：用户可能在等待期间
     * 继续编辑。反之，如果用户直接退出编辑页，ViewModel 一起被清掉，转写也随之取消——
     * 这是对的，因为那时刻已经没有任何正文可供写入，让它在后台跑完只会产出无处安放的文字。
     */
    fun transcribeRecording(resource: ResourceEntity) {
        lastRecording = resource
        if (!transcription.isEnabled()) return
        startTranscription(resource)
    }

    /** 重试上一次失败的转写。 */
    fun retryTranscription() {
        val resource = lastRecording ?: return
        startTranscription(resource)
    }

    /**
     * 等当前转写结束（成功、失败，或压根没在转）。
     *
     * 提交笔记和退出编辑页之前都要等一下：刚转出来的文字属于这条笔记，
     * 而写进正文这件事必须发生在页面还活着的时候——页面一关，承载正文的状态就没了，
     * 后台跑完的转写结果将无处可去。等待期间不阻塞界面，只是把「落笔」推迟几秒。
     */
    suspend fun awaitTranscription() {
        transcriptionJob?.join()
    }

    private fun startTranscription(resource: ResourceEntity) {
        transcriptionJob = viewModelScope.launch {
            transcriptionState = TranscriptionState.Busy

            val audio = localAudioFile(resource)
            if (audio == null) {
                transcriptionState = TranscriptionState.Failed(
                    getApplication<Application>().getString(R.string.transcription_audio_missing)
                )
                return@launch
            }

            when (val result = transcription.transcribe(audio)) {
                is TranscriptionResult.Success -> {
                    // 时间取自音频文件名，这样事后重试转写时，正文里记的仍然是录音那一刻
                    appendChannel.send(
                        transcription.appendBlock(resource.filename, result.text)
                    )
                    transcriptionState = TranscriptionState.Idle
                }

                is TranscriptionResult.Failure -> {
                    transcriptionState = TranscriptionState.Failed(result.message)
                }
            }
        }
    }

    /**
     * 把资源还原成可读的本地文件。
     *
     * 只认 `file://`：录音一定落在本应用目录里，`content://` 那种是远程账号的附件，
     * 要走下载，不在这条链路的射程内。
     */
    private fun localAudioFile(resource: ResourceEntity): File? {
        val uri = Uri.parse(resource.localUri ?: resource.uri)
        if (uri.scheme != "file") return null
        val path = uri.path ?: return null
        return File(path).takeIf { it.isFile && it.length() > 0L }
    }

    // ------------------------------------------------------------------ 网页正文读取

    /** 当前读取状态，供输入页显示「读取中 / 失败 + 重试」。 */
    var linkFetchState by mutableStateOf<LinkFetchState>(LinkFetchState.Idle)
        private set

    /**
     * 抓好的网页正文，等待写进正文。
     *
     * 和转写一样是一次性事件，用 Channel 而不是 StateFlow（见 [appendEvents]）。
     * 单独开一条通道而不是复用转写那条：两条链路的「追加完要提示什么」不一样，
     * 合在一起就只能提示一句模糊的话。
     */
    private val linkChannel = Channel<WebLinkAppend>(Channel.BUFFERED)
    val linkEvents: Flow<WebLinkAppend> = linkChannel.receiveAsFlow()

    /** 正在跑的抓取任务，供提交前等它收尾使用。 */
    private var linkJob: Job? = null

    /**
     * 读取一个网页的正文并追加到笔记里。
     *
     * 跑在 [viewModelScope]：抓取要几秒到十几秒，用户在等待期间多半还在写别的。
     * 已经开始一次抓取后再点不会重复发起——两次抓取同时写正文会互相插队。
     */
    fun fetchWebPage(url: String) {
        if (linkFetchState is LinkFetchState.Busy) return
        linkJob = viewModelScope.launch {
            linkFetchState = LinkFetchState.Busy(url)

            when (val result = webPageFetcher.fetch(url)) {
                is WebFetchResult.Success -> {
                    val article = result.article
                    linkChannel.send(
                        WebLinkAppend(
                            block = buildWebPageBlock(
                                label = getApplication<Application>().getString(R.string.web_page_label),
                                fetchedAt = LocalDateTime.now(),
                                title = article.title,
                                url = article.url,
                                markdown = article.markdown,
                                note = when {
                                    article.summaryOnly ->
                                        getApplication<Application>().getString(R.string.web_link_summary_only)

                                    article.truncated ->
                                        getApplication<Application>().getString(R.string.web_link_truncated)

                                    else -> null
                                },
                            ),
                            requestedUrl = url,
                            resolvedUrl = article.url,
                        )
                    )
                    linkFetchState = LinkFetchState.Idle
                }

                is WebFetchResult.Failure -> {
                    linkFetchState = LinkFetchState.Failed(describe(result.reason), url)
                }
            }
        }
    }

    /** 重试上一次失败的读取。 */
    fun retryFetchWebPage() {
        val failed = linkFetchState as? LinkFetchState.Failed ?: return
        fetchWebPage(failed.url)
    }

    /** 放弃正在跑的读取（离开输入页时调用，避免内容落进一个马上要消失的页面）。 */
    fun cancelFetchWebPage() {
        linkJob?.cancel()
        linkJob = null
        if (linkFetchState is LinkFetchState.Busy) {
            linkFetchState = LinkFetchState.Idle
        }
    }

    /**
     * 等当前读取结束。
     *
     * 提交笔记前要等一下：用户往往是「粘链接 → 抓取 → 立刻点发送」，
     * 不等的对话，抓下来的正文会随页面销毁而无处安放。
     */
    suspend fun awaitLinkFetch() {
        linkJob?.join()
    }

    private fun describe(reason: WebFetchError): String {
        val context = getApplication<Application>()
        return when (reason) {
            WebFetchError.INVALID_URL -> context.getString(R.string.web_link_error_invalid)
            WebFetchError.NETWORK -> context.getString(R.string.web_link_error_network)
            WebFetchError.HTTP -> context.getString(R.string.web_link_error_http)
            WebFetchError.NOT_HTML -> context.getString(R.string.web_link_error_not_html)
            WebFetchError.EMPTY -> context.getString(R.string.web_link_error_empty)
        }
    }

    // Identifier of the memo row that autosave writes to; null until the first autosave creates it.
    @Volatile
    var autosaveIdentifier: String? = null
    private val autosaveMutex = Mutex()

    suspend fun autosave(content: String, visibility: MemoVisibility, tags: List<String>, clearDraftOnCreate: Boolean = false): ApiResponse<MemoEntity> = autosaveMutex.withLock {
        withContext(NonCancellable) {
            val repository = memoService.getRepository()
            val identifier = autosaveIdentifier
            if (identifier == null) {
                repository.createMemo(content, visibility, uploadResources, tags, deferPush = true).also { response ->
                    response.suspendOnSuccess {
                        autosaveIdentifier = data.identifier
                        if (clearDraftOnCreate) {
                            updateDraft("")
                        }
                    }
                }
            } else {
                repository.updateMemo(identifier, content, null, visibility, tags, deferPush = true)
            }
        }
    }

    suspend fun flushAutosave(content: String, visibility: MemoVisibility, tags: List<String>, clearDraftOnCreate: Boolean = false): ApiResponse<MemoEntity> {
        val response = autosave(content, visibility, tags, clearDraftOnCreate)
        response.suspendOnSuccess {
            // Not cancellable: flush drops the deferred push before enqueueing the real one
            withContext(NonCancellable) {
                autosaveIdentifier?.let { memoService.getRepository().flushPendingPush(it) }
            }
            WidgetUpdater.updateWidgets(getApplication())
        }
        return response
    }

    // In viewModelScope, so it outlives the page's composition (leaving the app, app lock).
    fun flushAutosaveInBackground(content: String, visibility: MemoVisibility, tags: List<String>, clearDraftOnCreate: Boolean = false) {
        viewModelScope.launch {
            flushAutosave(content, visibility, tags, clearDraftOnCreate)
        }
    }

    suspend fun discardEmptyAutosave() = autosaveMutex.withLock {
        withContext(NonCancellable) {
            val identifier = autosaveIdentifier ?: return@withContext
            memoService.getRepository().deleteMemo(identifier)
            autosaveIdentifier = null
        }
    }

    suspend fun loadMemo(identifier: String): MemoEntity? = memoService.getRepository().getMemo(identifier)

    suspend fun createMemo(content: String, visibility: MemoVisibility, tags: List<String>): ApiResponse<MemoEntity> = withContext(viewModelScope.coroutineContext) {
        val response = memoService.getRepository().createMemo(content, visibility, uploadResources, tags)
        // Update widgets when a new memo is created
        response.suspendOnSuccess {
            WidgetUpdater.updateWidgets(getApplication())
        }
        response
    }

    suspend fun editMemo(identifier: String, content: String, visibility: MemoVisibility, tags: List<String>): ApiResponse<MemoEntity> = withContext(viewModelScope.coroutineContext) {
        val response = memoService.getRepository().updateMemo(identifier, content, uploadResources, visibility, tags)
        // Update widgets when a memo is edited
        response.suspendOnSuccess {
            WidgetUpdater.updateWidgets(getApplication())
        }
        response
    }

    fun updateDraft(content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            context.settingsDataStore.updateData { settings ->
                val index = settings.usersList.indexOfFirst { it.accountKey == settings.currentUser }
                if (index == -1) {
                    return@updateData settings
                }
                val users = settings.usersList.toMutableList()
                val user = users[index]
                users[index] = user.copy(settings = user.settings.copy(draft = content))
                settings.copy(usersList = users)
            }
        }
    }

    suspend fun upload(uri: Uri, memoIdentifier: String?): ApiResponse<ResourceEntity> = withContext(Dispatchers.IO) {
        // Held so an autosave cannot create the memo row while the file is being copied, which
        // would leave this resource unlinked (autosave updates never re-send resources).
        autosaveMutex.withLock {
            try {
                val mimeType = context.contentResolver.getType(uri)
                val extension = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                val filename = queryDisplayName(uri)
                    ?: ("attachment_${UUID.randomUUID()}" + if (extension.isNullOrBlank()) "" else ".$extension")

                memoService.getRepository()
                    .createResource(filename, mimeType?.toMediaTypeOrNull(), uri, memoIdentifier ?: autosaveIdentifier)
                    .suspendOnSuccess {
                        uploadResources.add(data)
                    }
            } catch (e: Exception) {
                ApiResponse.Failure.Exception(e)
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    return@use null
                }
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index == -1) {
                    null
                } else {
                    cursor.getString(index)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun deleteResource(resourceIdentifier: String) = viewModelScope.launch {
        memoService.getRepository().deleteResource(resourceIdentifier).suspendOnSuccess {
            uploadResources.removeIf { it.identifier == resourceIdentifier }
        }
    }

    /** 把录好的临时文件复制进资源目录并挂到笔记上，随后由调用方删掉临时文件。 */
    private suspend fun uploadRecording(file: File, memoIdentifier: String?): ApiResponse<ResourceEntity> =
        withContext(Dispatchers.IO) {
            // 与 upload() 同理先占住自动保存锁：自动保存不能在复制文件的途中把 memo 行建出来，
            // 否则这条资源就永远挂不上那条笔记（自动保存的更新不会重发资源列表）。
            autosaveMutex.withLock {
                try {
                    memoService.getRepository()
                        .createResource(
                            filename = file.name,
                            type = RECORDING_MIME_TYPE.toMediaTypeOrNull(),
                            contentUri = Uri.fromFile(file),
                            memoIdentifier = memoIdentifier ?: autosaveIdentifier,
                        )
                        .suspendOnSuccess {
                            uploadResources.add(data)
                        }
                } catch (e: Exception) {
                    ApiResponse.Failure.Exception(e)
                }
            }
        }

    override fun onCleared() {
        voiceRecorder.release()
        super.onCleared()
    }

    companion object {
        /** 与 [VoiceRecorder] 的输出容器一致：MPEG-4 里的 AAC。 */
        private const val RECORDING_MIME_TYPE = "audio/mp4"
    }
}
