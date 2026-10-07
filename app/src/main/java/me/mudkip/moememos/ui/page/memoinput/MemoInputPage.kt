package me.mudkip.moememos.ui.page.memoinput

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.TakePicture
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.skydoves.sandwich.suspendOnSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.mudkip.moememos.MoeMemosFileProvider
import me.mudkip.moememos.R
import me.mudkip.moememos.data.local.VoiceRecorder
import me.mudkip.moememos.data.model.MemoVisibility
import me.mudkip.moememos.data.model.ShareContent
import me.mudkip.moememos.data.web.stripSoleUrlBody
import me.mudkip.moememos.ext.popBackStackIfLifecycleIsResumed
import me.mudkip.moememos.ext.string
import me.mudkip.moememos.ext.suspendOnErrorMessage
import me.mudkip.moememos.ui.page.common.LocalRootNavController
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import me.mudkip.moememos.util.extractCustomTags
import me.mudkip.moememos.util.findFirstWebUrl
import me.mudkip.moememos.viewmodel.LocalMemos
import me.mudkip.moememos.viewmodel.LocalUserState
import me.mudkip.moememos.viewmodel.MemoInputViewModel
import me.mudkip.moememos.viewmodel.TranscriptionState

private const val MaxSelectableImages = 100

@Composable
fun MemoInputPage(
    viewModel: MemoInputViewModel = hiltViewModel(),
    memoIdentifier: String? = null,
    shareContent: ShareContent? = null
) {
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    val snackbarState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val navController = LocalRootNavController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val memosViewModel = LocalMemos.current
    val userStateViewModel = LocalUserState.current
    val currentAccount by userStateViewModel.currentAccount.collectAsStateWithLifecycle()
    val memo = remember { memosViewModel.memos.toList().find { it.identifier == memoIdentifier } }
    // What the editor was loaded from: `memo`, replaced by the database row once it is read (the list
    // copy above is not refreshed while a push or sync runs, so it can be older).
    var baseline by remember { mutableStateOf(memo) }
    val autosaveEnabled by viewModel.autosaveEnabled.collectAsStateWithLifecycle(initialValue = false)
    var autosaveIdentifier by rememberSaveable { mutableStateOf(memo?.identifier) }
    var autosaveDirty by remember { mutableStateOf(false) }
    var exiting by remember { mutableStateOf(false) }
    var initialContent by remember { mutableStateOf(memo?.content ?: "") }
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(memo?.content ?: "", TextRange(memo?.content?.length ?: 0)))
    }
    var visibilityMenuExpanded by remember { mutableStateOf(false) }
    var tagMenuExpanded by remember { mutableStateOf(false) }
    var photoImageUri by remember { mutableStateOf<Uri?>(null) }
    var showExitConfirmation by remember { mutableStateOf(false) }

    val defaultVisibility = userStateViewModel.currentUser?.defaultVisibility ?: MemoVisibility.PRIVATE
    var currentVisibility by remember { mutableStateOf(memo?.visibility ?: defaultVisibility) }

    val validMimeTypePrefixes = remember {
        setOf("text/")
    }

    // An existing memo nothing was changed in. Autosave must not write it back on Send/Back: if the
    // text is older than the database (see `baseline`), writing it would revert newer changes.
    fun isUntouchedExistingMemo(): Boolean {
        val base = baseline ?: return false
        return !autosaveDirty &&
            text.text == base.content &&
            currentVisibility == base.visibility &&
            viewModel.uploadResources.size == base.resources.size
    }

    fun submit() = coroutineScope.launch {
        // 还在录音就先收尾（并等它挂上笔记）：录好的东西不能因为点了发送就丢
        if (viewModel.voiceRecorder.isActive) {
            viewModel.finishRecording(autosaveIdentifier ?: memo?.identifier)
            // 刚录的这段还在转写，等它落进正文再提交：否则提交后页面销毁，
            // 转写被连带取消，这条语音就只有音频没有文字。
            viewModel.awaitTranscription()
        }
        // 抓取同理：用户常常「粘链接 → 抓取 → 立刻发送」，不等的话
        // 抓下来的正文会随着页面销毁而无处安放
        viewModel.awaitLinkFetch()
        val tags = extractCustomTags(text.text)

        if (autosaveEnabled && isUntouchedExistingMemo()) {
            exiting = true
            navController.popBackStack()
            return@launch
        }

        if (autosaveEnabled) {
            viewModel.flushAutosave(text.text, currentVisibility, tags.toList(), clearDraftOnCreate = shareContent == null).suspendOnSuccess {
                exiting = true
                memosViewModel.refreshLocalSnapshot()
                navController.popBackStack()
            }.suspendOnErrorMessage { message ->
                snackbarState.showSnackbar(message)
            }
            return@launch
        }

        memo?.let {
            viewModel.editMemo(memo.identifier, text.text, currentVisibility, tags.toList()).suspendOnSuccess {
                memosViewModel.refreshLocalSnapshot()
                navController.popBackStack()
            }.suspendOnErrorMessage { message ->
                snackbarState.showSnackbar(message)
            }
            return@launch
        }

        viewModel.createMemo(text.text, currentVisibility, tags.toList()).suspendOnSuccess {
            text = TextFieldValue("")
            viewModel.updateDraft("")
            memosViewModel.refreshLocalSnapshot()
            navController.popBackStack()
        }.suspendOnErrorMessage { message ->
            snackbarState.showSnackbar(message)
        }
    }

    fun handleExit() {
        // 离开页面时放弃未完成的录音：半截音频没有价值，留着只会变成垃圾文件
        if (viewModel.voiceRecorder.isActive) {
            viewModel.cancelRecording()
        }
        // 正在抓的网页同样放弃：正文马上要随页面一起消失，抓完也无处可写
        viewModel.cancelFetchWebPage()
        if (autosaveEnabled) {
            coroutineScope.launch {
                // 转写还在跑就先等它：文字要先落进正文，才能被这次自动保存带上
                viewModel.awaitTranscription()
                // Only a row this editor created may be discarded. `memo` is also null when editing a
                // memo the list has not loaded (e.g. after process death); that memo must not be deleted.
                val autosaveRow = viewModel.autosaveIdentifier
                val ownsAutosaveRow = autosaveRow == null || autosaveRow != memoIdentifier
                if (ownsAutosaveRow && text.text.isEmpty() && viewModel.uploadResources.isEmpty()) {
                    viewModel.discardEmptyAutosave()
                } else if (!isUntouchedExistingMemo()) {
                    viewModel.flushAutosave(
                        text.text,
                        currentVisibility,
                        extractCustomTags(text.text).toList(),
                        clearDraftOnCreate = shareContent == null
                    )
                }
                exiting = true
                memosViewModel.refreshLocalSnapshot()
                navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
            }
            return
        }
        coroutineScope.launch {
            // 同上：关页面前让转写把文字交出来
            viewModel.awaitTranscription()
            if (text.text != initialContent || viewModel.uploadResources.size != (baseline?.resources?.size ?: 0)) {
                showExitConfirmation = true
            } else {
                navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
            }
        }
    }

    fun uploadImages(uris: List<Uri>) = coroutineScope.launch {
        uris.take(MaxSelectableImages).forEach { uri ->
            viewModel.upload(uri, autosaveIdentifier ?: memo?.identifier).suspendOnErrorMessage { message ->
                snackbarState.showSnackbar(message)
            }
        }
        delay(300)
        focusRequester.requestFocus()
    }

    fun uploadImage(uri: Uri) {
        uploadImages(listOf(uri))
    }

    val pickImages = rememberLauncherForActivityResult(
        PickMultipleVisualMedia(MaxSelectableImages)
    ) { uris ->
        if (uris.isNotEmpty()) {
            uploadImages(uris)
        }
    }

    val takePhoto = rememberLauncherForActivityResult(TakePicture()) { success ->
        if (success) {
            photoImageUri?.let { uploadImage(it) }
        }
    }

    val pickAttachment = rememberLauncherForActivityResult(OpenDocument()) { uri ->
        uri?.let {
            coroutineScope.launch {
                viewModel.upload(it, autosaveIdentifier ?: memo?.identifier).suspendOnErrorMessage { message ->
                    snackbarState.showSnackbar(message)
                }
            }
        }
    }

    // ---------------------------------------------------------------- 语音转写

    val transcriptionState = viewModel.transcriptionState

    // 转写结果是一次性事件，收到就追加到正文末尾，并把光标跟过去。
    // 必须放在 LaunchedEffect 里收集：转写可能比用户停留更久，
    // 也可能在录音结束前就回来了。
    LaunchedEffect(Unit) {
        viewModel.appendEvents.collect { block ->
            val updated = text.text + block
            text = TextFieldValue(updated, TextRange(updated.length))
            autosaveDirty = true
            snackbarState.showSnackbar(context.getString(R.string.transcription_done))
        }
    }

    // ---------------------------------------------------------------- 网页正文读取

    // 正文里出现的第一个链接，用来提示「可以抓正文」。按文本变化重算：
    // 输入框里的字符串本来就每次敲键都会换一个，正则很便宜，不值得再加一层缓存。
    val detectedUrl = remember(text.text) { findFirstWebUrl(text.text) }

    // 已经抓过一次的地址不再重复提示：正文里已经带着它了，一直提示只会碍事。
    var fetchedUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.linkEvents.collect { append ->
            // 正文里如果只有用户刚粘的那条地址，抓完就把它换成网页块——
            // 那行只是触发抓取的动作，块头里已经带了出处，留着就成了同一个地址写两遍。
            val base = stripSoleUrlBody(
                text = text.text,
                requestedUrl = append.requestedUrl,
                resolvedUrl = append.resolvedUrl,
            )
            val updated = if (base.isBlank()) {
                append.block.trimStart()
            } else {
                base + append.block
            }
            text = TextFieldValue(updated, TextRange(updated.length))
            autosaveDirty = true
            snackbarState.showSnackbar(context.getString(R.string.web_link_fetched))
        }
    }

    // ---------------------------------------------------------------- 录音

    val recorder = viewModel.voiceRecorder

    fun startRecording() {
        if (!viewModel.startRecording()) {
            coroutineScope.launch {
                snackbarState.showSnackbar(context.getString(R.string.recording_failed))
            }
        }
    }

    val recordAudioPermission = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (granted) {
            startRecording()
        } else {
            coroutineScope.launch {
                snackbarState.showSnackbar(context.getString(R.string.microphone_permission_denied))
            }
        }
    }

    fun requestRecording() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            startRecording()
        } else {
            recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    BackHandler {
        handleExit()
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            MemoInputTopBar(
                isEditMode = memo != null,
                canSubmit = text.text.isNotEmpty() || viewModel.uploadResources.isNotEmpty(),
                onClose = { handleExit() },
                onSubmit = { submit() }
            )
        },
        bottomBar = {
            Column {
                LinkFetchBar(
                    state = viewModel.linkFetchState,
                    suggestedUrl = detectedUrl?.takeIf { it != fetchedUrl },
                    onFetch = { url ->
                        fetchedUrl = url
                        viewModel.fetchWebPage(url)
                    },
                    onRetry = { viewModel.retryFetchWebPage() },
                )
                TranscriptionStatusBar(
                    state = transcriptionState,
                    onRetry = { viewModel.retryTranscription() },
                )
                if (recorder.isActive) {
                    RecordingBar(
                        recorder = recorder,
                        onTick = { recorder.tick() },
                        onPauseToggle = {
                            if (recorder.state == VoiceRecorder.State.Recording) {
                                recorder.pause()
                            } else {
                                recorder.resume()
                            }
                        },
                        onDiscard = {
                            viewModel.cancelRecording()
                            coroutineScope.launch {
                                snackbarState.showSnackbar(context.getString(R.string.recording_discarded))
                            }
                        },
                        onFinish = {
                            coroutineScope.launch {
                                val response = viewModel.finishRecording(autosaveIdentifier ?: memo?.identifier)
                                if (response == null) {
                                    snackbarState.showSnackbar(context.getString(R.string.recording_too_short))
                                } else {
                                    response.suspendOnErrorMessage { message ->
                                        snackbarState.showSnackbar(message)
                                    }
                                }
                            }
                        }
                    )
                } else {
                    MemoInputBottomBar(
                        currentAccount = currentAccount,
                        currentVisibility = currentVisibility,
                        showSpaceVisibility = memo?.visibility == MemoVisibility.SPACE,
                        visibilityMenuExpanded = visibilityMenuExpanded,
                        onVisibilityExpandedChange = { visibilityMenuExpanded = it },
                        onVisibilitySelected = { currentVisibility = it },
                        tags = memosViewModel.tags.toList(),
                        tagMenuExpanded = tagMenuExpanded,
                        onTagExpandedChange = { tagMenuExpanded = it },
                        onHashTagClick = {
                            text = replaceSelection(text, "#")
                        },
                        onTagSelected = { tag ->
                            text = replaceSelection(text, "#$tag ")
                        },
                        onToggleTodoItem = {
                            text = toggleTodoItemInText(text)
                        },
                        onPickImage = {
                            pickImages.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                        },
                        onPickAttachment = {
                            pickAttachment.launch(arrayOf("*/*"))
                        },
                        onTakePhoto = {
                            try {
                                val uri = MoeMemosFileProvider.getImageUri(navController.context)
                                photoImageUri = uri
                                takePhoto.launch(uri)
                            } catch (e: ActivityNotFoundException) {
                                coroutineScope.launch {
                                    snackbarState.showSnackbar(e.localizedMessage ?: "Unable to take picture.")
                                }
                            }
                        },
                        onRecordAudio = { requestRecording() },
                        onFetchLink = {
                            val url = detectedUrl
                            if (url == null) {
                                coroutineScope.launch {
                                    snackbarState.showSnackbar(context.getString(R.string.web_link_not_found))
                                }
                            } else {
                                fetchedUrl = url
                                viewModel.fetchWebPage(url)
                            }
                        },
                        onFormat = { format ->
                            text = applyMarkdownFormatToText(text, format)
                        }
                    )
                }
            }
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarState)
        }
    ) { innerPadding ->
        MemoInputEditor(
            modifier = Modifier.padding(innerPadding),
            text = text,
            onTextChange = { updated ->
                if (
                    text.text != updated.text &&
                    updated.selection.start == updated.selection.end &&
                    updated.text.length == text.text.length + 1 &&
                    updated.selection.start > 0 &&
                    updated.text[updated.selection.start - 1] == '\n'
                ) {
                    val handled = handleEnterInText(text)
                    if (handled != null) {
                        text = handled
                        return@MemoInputEditor
                    }
                }
                text = updated
            },
            focusRequester = focusRequester,
            validMimeTypePrefixes = validMimeTypePrefixes,
            onDroppedText = { droppedText ->
                text = text.copy(text = text.text + droppedText)
            },
            uploadResources = viewModel.uploadResources.toList(),
            inputViewModel = viewModel
        )
    }

    if (showExitConfirmation) {
        SaveChangesDialog(
            onSave = {
                showExitConfirmation = false
                submit()
            },
            onDiscard = {
                showExitConfirmation = false
                text = TextFieldValue("")
                navController.popBackStackIfLifecycleIsResumed(lifecycleOwner)
            },
            onDismiss = {
                showExitConfirmation = false
            }
        )
    }

    LaunchedEffect(Unit) {
        viewModel.autosaveIdentifier = autosaveIdentifier
        viewModel.uploadResources.clear()
        when {
            memo != null -> {
                viewModel.uploadResources.addAll(memo.resources)
                initialContent = memo.content
                // Adopt the database row if the list copy was stale and nothing was edited yet
                val fresh = viewModel.loadMemo(memo.identifier)
                if (fresh != null && fresh != memo && isUntouchedExistingMemo()) {
                    baseline = fresh
                    initialContent = fresh.content
                    currentVisibility = fresh.visibility
                    viewModel.uploadResources.clear()
                    viewModel.uploadResources.addAll(fresh.resources)
                    text = TextFieldValue(fresh.content, TextRange(fresh.content.length))
                }
            }

            shareContent != null -> {
                text = TextFieldValue(shareContent.text, TextRange(shareContent.text.length))
                for (item in shareContent.images) {
                    uploadImage(item)
                }
            }

            else -> {
                // After process death with an autosaved row, the restored text is newer than the draft
                if (autosaveIdentifier == null) {
                    viewModel.draft.first()?.let {
                        text = TextFieldValue(it, TextRange(it.length))
                    }
                }
            }
        }
        delay(300)
        focusRequester.requestFocus()
    }

    // Restarts per change; a restart cancels a previous run still waiting for the autosave mutex,
    // so at most one write runs and one (the newest) waits.
    LaunchedEffect(autosaveEnabled, text.text, currentVisibility, viewModel.uploadResources.size) {
        if (!autosaveEnabled) {
            return@LaunchedEffect
        }
        autosaveIdentifier = autosaveIdentifier ?: viewModel.autosaveIdentifier
        if (autosaveIdentifier == null && text.text.isEmpty() && viewModel.uploadResources.isEmpty()) {
            return@LaunchedEffect
        }
        if (isUntouchedExistingMemo()) {
            return@LaunchedEffect
        }
        autosaveDirty = true
        viewModel.autosave(
            text.text,
            currentVisibility,
            extractCustomTags(text.text).toList(),
            clearDraftOnCreate = shareContent == null
        ).suspendOnSuccess {
            autosaveIdentifier = data.identifier
        }
    }

    // Leaving the app: rewrite the latest text and push it now. The deferred push only lives as long
    // as the process, and the app lock tears this page down on return without calling handleExit.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && autosaveEnabled && autosaveDirty && !exiting) {
                viewModel.flushAutosaveInBackground(
                    text.text,
                    currentVisibility,
                    extractCustomTags(text.text).toList(),
                    clearDraftOnCreate = shareContent == null
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (autosaveEnabled) {
                // Already flushed by submit/handleExit; a system-initiated dispose leaves needsSync
                // set, so the deferred push or the next sync uploads the row.
                return@onDispose
            }
            if (memo == null && shareContent == null) {
                viewModel.updateDraft(text.text)
            }
        }
    }
}

/**
 * 转写状态条，贴在输入页底部工具栏上方。
 *
 * 只占一行，且 `Idle` 时完全不占空间——转写是大多数时候不存在的背景过程，
 * 不该常驻一块地方提醒用户它的存在。
 *
 * 失败时把重试按钮放在这里而不是弹窗：转写失败不影响录音已经存好这件事，
 * 用户完全可以继续写笔记，所以不该用弹窗拦住他。
 */
@Composable
private fun TranscriptionStatusBar(
    state: TranscriptionState,
    onRetry: () -> Unit,
) {
    when (state) {
        is TranscriptionState.Idle -> Unit

        is TranscriptionState.Busy -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
            Text(
                R.string.transcription_busy.string,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        is TranscriptionState.Failed -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp)
        ) {
            Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onRetry) {
                Text(R.string.transcription_retry.string)
            }
        }
    }
}
