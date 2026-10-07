package me.mudkip.moememos.data.transcription

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import me.mudkip.moememos.R
import me.mudkip.moememos.data.local.RecordingFileStamp
import me.mudkip.moememos.data.local.TranscriptionPrefs
import java.io.File
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 转写调度：按用户选的引擎把音频变成文字。
 *
 * 上层（输入页、设置页）只跟这里打交道，不认识 sherpa-onnx，也不认识云端
 * 接口的形状。换引擎、加引擎都只动这一个包。
 *
 * 这里还负责一个容易被忽略的细节：**文字落进正文时的样子**。转写正文要带上
 * 时间戳，而「录音是什么时候录的」只有音频文件名知道（见 [appendBlock]）。
 */
@Singleton
class TranscriptionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val onDevice: OnDeviceTranscriber,
    private val cloud: CloudTranscriber,
) {

    /** 当前选定的引擎。 */
    fun engine(): TranscriptionPrefs.Engine = TranscriptionPrefs.engine(context)

    /** 转写是否启用。关掉时录音照常，只是不产出文字。 */
    fun isEnabled(): Boolean = TranscriptionPrefs.isActive(context)

    /** 当前引擎是否可以立刻干活。不可用时带上原因，供界面直接展示。 */
    suspend fun availability(): TranscriptionAvailability = when (engine()) {
        TranscriptionPrefs.Engine.OFF -> TranscriptionAvailability.Unavailable(
            reason = context.getString(R.string.transcription_disabled),
        )

        TranscriptionPrefs.Engine.ON_DEVICE -> onDevice.availability()
        TranscriptionPrefs.Engine.CLOUD -> cloud.availability()
    }

    /**
     * 转写一段音频。调用方必须已经确认 [isEnabled] 为真。
     *
     * 任何失败都以 [TranscriptionResult.Failure] 返回，不抛异常——
     * 转写失败绝不能波及「录音已经存好了」这件事。
     */
    suspend fun transcribe(audio: File): TranscriptionResult = when (engine()) {
        TranscriptionPrefs.Engine.OFF -> TranscriptionResult.Failure(
            context.getString(R.string.transcription_disabled)
        )

        TranscriptionPrefs.Engine.ON_DEVICE -> onDevice.transcribe(audio)
        TranscriptionPrefs.Engine.CLOUD -> cloud.transcribe(audio)
    }

    /** 释放端侧模型占用的内存。设置页关掉端侧引擎时调用。 */
    suspend fun releaseOnDeviceModel() = onDevice.shutdown()

    /**
     * 组装要追加进正文的段落。
     *
     * 形如：
     * ```
     *
     * [语音 22:15]
     * 明天下午三点跟张工对一下接口文档。
     * ```
     *
     * 时间取自录音文件名里那份时间戳（`voice-20261006-221532.m4a`），
     * 而不是「转写发生的时刻」：这样事后重试转写、或隔天补转，正文里记的
     * 仍然是当初录音的时间，而不是操作时间。
     *
     * @param audioFileName 音频文件名，用于还原录音时刻。
     * @param text 转写文本。
     * @param fallbackTime 文件名里认不出时间时用的时刻。
     */
    fun appendBlock(
        audioFileName: String,
        text: String,
        fallbackTime: LocalDateTime = LocalDateTime.now(),
    ): String = buildTranscriptionBlock(
        label = context.getString(R.string.transcription_voice_label),
        // 时间取自录音文件名，认不出来才退回当前时刻
        recordingTime = RecordingFileStamp.parse(audioFileName) ?: fallbackTime,
        text = text,
    )
}
