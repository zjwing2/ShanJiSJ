package me.mudkip.moememos.data.local

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.time.LocalDateTime
import kotlin.math.sqrt

/**
 * 语音录制（闪念快记）。
 *
 * 只做三件事：录成 `.m4a`、给出实时电平、把成品文件交出去。它不碰数据库——
 * 文件先落在 `cacheDir/voice/`，由
 * [me.mudkip.moememos.viewmodel.MemoInputViewModel] 复制进资源目录后立刻删除。
 *
 * ## 编码选择
 *
 * AAC / MPEG-4 容器（`.m4a`）、16 kHz 单声道、32 kbps。16 kHz 是人声足够、
 * 又普遍被播放器识别的下限；32 kbps 下 1 分钟约 240 KB、1 小时约 14 MB，
 * 无论对 OneDrive 同步还是手机存储都算克制。
 *
 * ## 状态放在哪里
 *
 * 实例由 [me.mudkip.moememos.viewmodel.MemoInputViewModel] 持有，而不放在 Compose 里：
 * 录音中途转屏、切后台回来都不会中断，ViewModel 清空时 [release] 兜底丢弃半截文件。
 */
class VoiceRecorder(private val context: Context) {

    enum class State { Idle, Recording, Paused }

    var state by mutableStateOf(State.Idle)
        private set

    /** 已录制时长（毫秒）。暂停期间不再增长。 */
    var elapsedMillis by mutableLongStateOf(0L)
        private set

    /** 最近 [BAR_COUNT] 次音量采样，0f..1f，供波形图直接使用。 */
    var levels by mutableStateOf(List(BAR_COUNT) { 0f })
        private set

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var accumulated = 0L
    private var runningSince = 0L

    val isActive: Boolean get() = state != State.Idle

    /** 开始录制。返回 null 表示启动失败（通常是麦克风被别的应用占用）。 */
    fun start(): File? {
        if (isActive) return outputFile
        val dir = File(context.cacheDir, "voice").also { it.mkdirs() }
        val file = File(dir, RecordingFileStamp.fileNameFor(LocalDateTime.now()))
        val recorderInstance = newRecorder()
        return try {
            recorderInstance.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorderInstance.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorderInstance.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorderInstance.setAudioChannels(1)
            recorderInstance.setAudioSamplingRate(SAMPLE_RATE)
            recorderInstance.setAudioEncodingBitRate(BIT_RATE)
            recorderInstance.setOutputFile(file.absolutePath)
            recorderInstance.prepare()
            recorderInstance.start()
            recorder = recorderInstance
            outputFile = file
            accumulated = 0L
            runningSince = System.currentTimeMillis()
            elapsedMillis = 0L
            levels = List(BAR_COUNT) { 0f }
            state = State.Recording
            file
        } catch (e: Exception) {
            runCatching { recorderInstance.release() }
            runCatching { file.delete() }
            recorder = null
            outputFile = null
            state = State.Idle
            null
        }
    }

    fun pause() {
        val instance = recorder ?: return
        if (state != State.Recording) return
        runCatching { instance.pause() }.onSuccess {
            accumulated += System.currentTimeMillis() - runningSince
            elapsedMillis = accumulated
            state = State.Paused
        }
    }

    fun resume() {
        val instance = recorder ?: return
        if (state != State.Paused) return
        runCatching { instance.resume() }.onSuccess {
            runningSince = System.currentTimeMillis()
            state = State.Recording
        }
    }

    /** 由 UI 的计时循环驱动：推进时长、采样电平。 */
    fun tick() {
        when (state) {
            State.Recording -> {
                elapsedMillis = accumulated + (System.currentTimeMillis() - runningSince)
                val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                levels = levels.drop(1) + normalize(amplitude)
            }

            State.Paused -> elapsedMillis = accumulated
            State.Idle -> Unit
        }
    }

    /**
     * 结束并交出文件。返回 null 表示这段录音太短、编码器没吐出任何数据
     * （`MediaRecorder.stop()` 在这种情况会抛异常），临时文件已一并删除。
     */
    fun finish(): File? {
        val instance = recorder ?: return null
        val file = outputFile
        val tooShort = elapsedMillis < MIN_MILLIS
        reset()

        val stopped = runCatching { instance.stop() }.isSuccess
        runCatching { instance.release() }

        if (stopped && !tooShort && file != null && file.length() > 0L) return file
        file?.let { runCatching { it.delete() } }
        return null
    }

    /** 放弃本次录制：停止并删除临时文件。 */
    fun discard() {
        val instance = recorder
        val file = outputFile
        reset()
        if (instance != null) {
            runCatching { instance.stop() }
            runCatching { instance.release() }
        }
        file?.let { runCatching { it.delete() } }
    }

    /** ViewModel 销毁时兜底：绝不留下半截录音。 */
    fun release() = discard()

    private fun reset() {
        recorder = null
        outputFile = null
        state = State.Idle
        accumulated = 0L
        elapsedMillis = 0L
        levels = List(BAR_COUNT) { 0f }
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private fun normalize(amplitude: Int): Float {
        if (amplitude <= 0) return 0f
        val raw = (amplitude / MAX_AMPLITUDE).coerceIn(0f, 1f)
        // 开方：把安静说话时偏小的采样值抬起来，柱状图才有动态
        return sqrt(raw)
    }

    companion object {
        /** 波形柱子数量，必须与 UI 的柱子数一致。 */
        const val BAR_COUNT = 28

        /** 短于这个时长的录音直接丢弃。 */
        private const val MIN_MILLIS = 800L

        private const val SAMPLE_RATE = 16_000
        private const val BIT_RATE = 32_000
        private const val MAX_AMPLITUDE = 32_767f
    }
}
