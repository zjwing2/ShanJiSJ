package me.mudkip.moememos.ui.component

import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.mudkip.moememos.R
import me.mudkip.moememos.data.local.entity.ResourceEntity
import me.mudkip.moememos.data.model.ResourceRepresentable
import me.mudkip.moememos.ext.string
import me.mudkip.moememos.viewmodel.AudioTranscribeState
import me.mudkip.moememos.viewmodel.LocalMemos
import me.mudkip.moememos.viewmodel.LocalUserState
import java.io.File

/**
 * 音频附件的可播放芯片（语音闪念的主力形态）。
 *
 * 与普通附件的区别：点一下直接在本应用里播放，而不是拉起系统分享面板——
 * 录完一段话却要选一遍「用哪个应用播放」实在说不过去。
 *
 * - 本地账号（[me.mudkip.moememos.data.model.Account.Local]）下文件就在应用目录，直接播；
 * - 远程账号的文件在服务器上，退回老的分享路径（下载 + 交给系统播放器）；
 * - 带 [onRemove] 时（编辑中的草稿）右上角再多一个菜单，提供「用其他应用打开 / 删除」。
 * - 带 [transcribeMemoIdentifier] 时（详情页的老音频）右上角出现「转写」入口，
 *   把这条录音补转成文字、追加到所属笔记正文。
 */
@Composable
fun AudioAttachment(
    resource: ResourceRepresentable,
    onRemove: (() -> Unit)? = null,
    transcribeMemoIdentifier: String? = null
) {
    val context = LocalContext.current
    val memosViewModel = LocalMemos.current
    val userStateViewModel = LocalUserState.current
    val scope = rememberCoroutineScope()
    val player = remember(resource.uri) { AudioClipPlayer() }
    var menuExpanded by remember { mutableStateOf(false) }
    var transcribeMenuExpanded by remember { mutableStateOf(false) }

    // 只有详情页（只读、且能定位到所属笔记）才出现补转入口
    val transcribeEntity = (resource as? ResourceEntity)
        .takeIf { transcribeMemoIdentifier != null && onRemove == null }
    val transcribeState = transcribeEntity?.let { memosViewModel.audioTranscribeStates[it.identifier] }

    // 状态「变化」才提示：回到详情页时残留的 Done/Failed 不应再弹一次
    var lastNotifiedState by remember { mutableStateOf(transcribeState) }
    LaunchedEffect(transcribeState) {
        val current = transcribeState
        if (current == lastNotifiedState) return@LaunchedEffect
        lastNotifiedState = current
        when (current) {
            is AudioTranscribeState.Failed ->
                Toast.makeText(context, current.message, Toast.LENGTH_LONG).show()
            AudioTranscribeState.Done ->
                Toast.makeText(context, R.string.transcription_done.string, Toast.LENGTH_SHORT).show()
            else -> Unit
        }
    }

    DisposableEffect(resource.uri) {
        onDispose { player.release() }
    }

    // 播放期间按 200ms 刷新进度；isPlaying 变 false（播完 / 暂停 / 释放）即退出循环
    LaunchedEffect(player.isPlaying) {
        while (player.isPlaying) {
            player.syncPosition()
            delay(200)
        }
    }

    val localFile = remember(resource.localUri, resource.uri) {
        existingLocalFile(resource)
    }

    val trailingMenu: (@Composable () -> Unit)? = when {
        transcribeEntity != null -> {
            {
                when (transcribeState) {
                    is AudioTranscribeState.Running -> CircularProgressIndicator(
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                        strokeWidth = 2.dp
                    )

                    AudioTranscribeState.Done -> Icon(
                        Icons.Outlined.Check,
                        contentDescription = R.string.transcription_done.string,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                        tint = MaterialTheme.colorScheme.primary
                    )

                    else -> IconButton(
                        onClick = { transcribeMenuExpanded = true },
                        modifier = Modifier.size(AssistChipDefaults.IconSize + 12.dp)
                    ) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = R.string.more_options.string,
                            modifier = Modifier.size(AssistChipDefaults.IconSize)
                        )
                    }
                }
            }
        }

        onRemove != null -> {
            {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(AssistChipDefaults.IconSize + 12.dp)
                ) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = R.string.more_options.string,
                        modifier = Modifier.size(AssistChipDefaults.IconSize)
                    )
                }
            }
        }

        else -> null
    }

    AssistChip(
        onClick = {
            val file = localFile
            if (file != null) {
                player.toggle(file)
                return@AssistChip
            }
            scope.launch {
                val shared = shareAttachment(
                    context = context,
                    resource = resource,
                    okHttpClient = userStateViewModel.okHttpClient,
                    cacheCanonical = { resourceIdentifier, downloadedUri ->
                        resourceCacheUpdater(memosViewModel, resourceIdentifier, downloadedUri)
                    }
                )
                if (!shared) {
                    Toast.makeText(context, R.string.failed_to_open_attachment.string, Toast.LENGTH_SHORT).show()
                }
            }
        },
        label = { Text(resource.filename) },
        leadingIcon = {
            Icon(
                if (player.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                contentDescription = stringResource(
                    if (player.isPlaying) R.string.pause_audio else R.string.play_audio
                ),
                modifier = Modifier.size(AssistChipDefaults.IconSize)
            )
        },
        trailingIcon = trailingMenu
    )

    if (transcribeEntity != null) {
        DropdownMenu(
            expanded = transcribeMenuExpanded,
            onDismissRequest = { transcribeMenuExpanded = false },
            properties = PopupProperties(focusable = false)
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (transcribeState is AudioTranscribeState.Failed) {
                            R.string.transcription_retry.string
                        } else {
                            R.string.transcribe_action.string
                        }
                    )
                },
                onClick = {
                    transcribeMenuExpanded = false
                    val memoIdentifier = transcribeMemoIdentifier ?: return@DropdownMenuItem
                    memosViewModel.transcribeAudioAttachment(memoIdentifier, transcribeEntity)
                }
            )
        }
    }

    if (onRemove != null) {
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            properties = PopupProperties(focusable = false)
        ) {
            DropdownMenuItem(
                text = { Text(R.string.open.string) },
                onClick = {
                    menuExpanded = false
                    player.release()
                    scope.launch {
                        val shared = shareAttachment(
                            context = context,
                            resource = resource,
                            okHttpClient = userStateViewModel.okHttpClient,
                            cacheCanonical = { resourceIdentifier, downloadedUri ->
                                resourceCacheUpdater(memosViewModel, resourceIdentifier, downloadedUri)
                            }
                        )
                        if (!shared) {
                            Toast.makeText(
                                context,
                                R.string.failed_to_open_attachment.string,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            )
            DropdownMenuItem(
                text = { Text(R.string.remove.string) },
                onClick = {
                    menuExpanded = false
                    player.release()
                    onRemove.invoke()
                }
            )
        }
    }
}

/**
 * 极简单文件播放器。同一时刻只保留一个 MediaPlayer，复用 / 暂停 / 释放都很明确。
 */
private class AudioClipPlayer {
    private var player: MediaPlayer? = null

    var isPlaying by mutableStateOf(false)
        private set
    var positionMillis by mutableLongStateOf(0L)
        private set
    var durationMillis by mutableLongStateOf(0L)
        private set

    fun toggle(file: File) {
        if (isPlaying) {
            pause()
            return
        }
        // 已经准备好且停在中途 → 续播；否则从头开一个
        val instance = player
        if (instance != null && positionMillis > 0L) {
            runCatching { instance.start() }.onSuccess { isPlaying = true }
            return
        }
        start(file)
    }

    private fun start(file: File) {
        release()
        if (!file.exists()) return
        val instance = MediaPlayer()
        runCatching {
            instance.setDataSource(file.absolutePath)
            instance.prepare()
            instance.setOnCompletionListener {
                isPlaying = false
                positionMillis = 0L
            }
            instance.start()
            player = instance
            durationMillis = instance.duration.toLong().coerceAtLeast(0L)
            isPlaying = true
        }.onFailure {
            runCatching { instance.release() }
            player = null
            isPlaying = false
        }
    }

    private fun pause() {
        runCatching { player?.pause() }
        isPlaying = false
    }

    fun syncPosition() {
        positionMillis = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
    }

    fun release() {
        val instance = player
        player = null
        isPlaying = false
        positionMillis = 0L
        durationMillis = 0L
        if (instance != null) {
            runCatching { instance.stop() }
            runCatching { instance.release() }
        }
    }
}
