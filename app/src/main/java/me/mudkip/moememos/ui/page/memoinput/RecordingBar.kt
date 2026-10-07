package me.mudkip.moememos.ui.page.memoinput

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.mudkip.moememos.R
import me.mudkip.moememos.data.local.VoiceRecorder
import me.mudkip.moememos.ui.component.ActionIconButton
import java.util.Locale

/**
 * 录制中替换底部工具栏的录制条：时长、实时波形、暂停/继续、放弃、完成。
 *
 * 状态直接从 [recorder] 读，因此每 80 毫秒的重组只发生在本组件内部，
 * 不会带着整个编辑器（含输入框）一起重组。
 */
@Composable
internal fun RecordingBar(
    recorder: VoiceRecorder,
    onTick: () -> Unit,
    onPauseToggle: () -> Unit,
    onDiscard: () -> Unit,
    onFinish: () -> Unit,
) {
    LaunchedEffect(recorder) {
        while (true) {
            onTick()
            delay(80)
        }
    }

    val paused = recorder.state == VoiceRecorder.State.Paused
    val accent = if (paused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error

    BottomAppBar {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = formatDuration(recorder.elapsedMillis),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = accent,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Waveform(
                levels = recorder.levels,
                color = accent,
                modifier = Modifier
                    .weight(1f)
                    .height(26.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            ActionIconButton(
                label = stringResource(
                    if (paused) R.string.resume_recording else R.string.pause_recording
                ),
                onClick = onPauseToggle,
            ) {
                Icon(
                    if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                    contentDescription = null,
                )
            }
            ActionIconButton(
                label = stringResource(R.string.discard_recording),
                onClick = onDiscard,
            ) {
                Icon(Icons.Outlined.Close, contentDescription = null)
            }
            ActionIconButton(
                label = stringResource(R.string.finish_recording),
                onClick = onFinish,
            ) {
                Icon(Icons.Outlined.Check, contentDescription = null)
            }
        }
    }
}

@Composable
private fun Waveform(
    levels: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        if (levels.isEmpty()) return@Canvas
        val gap = 2.dp.toPx()
        val barWidth = (size.width - gap * (levels.size - 1)) / levels.size
        if (barWidth <= 0f) return@Canvas
        val radius = CornerRadius(barWidth / 2f)
        levels.forEachIndexed { index, level ->
            val barHeight = (size.height * (0.16f + 0.84f * level.coerceIn(0f, 1f)))
                .coerceAtMost(size.height)
            drawRoundRect(
                color = color,
                topLeft = Offset(
                    x = index * (barWidth + gap),
                    y = (size.height - barHeight) / 2f,
                ),
                size = Size(barWidth, barHeight),
                cornerRadius = radius,
            )
        }
    }
}

/** `00:12` / `1:02:03` */
internal fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
