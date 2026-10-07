package me.mudkip.moememos.data.transcription

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.mudkip.moememos.R
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 端侧离线转写：sherpa-onnx + SenseVoice。
 *
 * 整条链路是「音频文件 → 解码成 PCM → VAD 切句 → 逐句识别 → 拼接文本」，
 * 全程不联网、不需要任何账号或密钥，音频一个字节都不出手机。
 *
 * ## 为什么不流式
 *
 * SenseVoice 本身是非流式模型，而且闪念笔记的录音都是「录完再转」，
 * 没有边说边要字的场景。等录音结束一次性转完，比在录音过程中维护
 * 一个流式状态机简单得多，也不会和用户正在编辑的正文抢状态。
 *
 * ## 资源为什么用完要还
 *
 * 加载后的模型常驻内存约 250–300 MB（228 MB 权重 + ONNX Runtime 的推理区）。
 * 这对一台 16 GB 的手机不算什么，但**长期挂着一个 300 MB 的会话**在后台
 * 毫无意义——系统内存紧张时它会成为第一个被杀的对象，反而让下次转写更慢。
 * 所以这里采取「用完保留 90 秒再释放」：连续录音几次不用重复加载，
 * 而闲置超过一分半就还回去。[MAX_AUDIO_SECONDS] 以上的长音频也会被拒绝，
 * 避免一次性解出几百 MB 的浮点数组。
 */
@Singleton
class OnDeviceTranscriber @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val models: TranscriptionModelManager,
) : TranscriptionEngine {

    override val id: String = "on-device"

    /** 串行化：同一个识别器不能被两段音频同时使用。 */
    private val mutex = Mutex()

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var releaseJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun availability(): TranscriptionAvailability =
        if (models.isReady()) {
            TranscriptionAvailability.Ready
        } else {
            TranscriptionAvailability.Unavailable(
                reason = context.getString(R.string.transcription_model_missing),
                needsDownload = true,
            )
        }

    override suspend fun transcribe(audio: File): TranscriptionResult = mutex.withLock {
        if (!models.isReady()) {
            return@withLock TranscriptionResult.Failure(
                context.getString(R.string.transcription_model_missing)
            )
        }
        if (!audio.isFile || audio.length() == 0L) {
            return@withLock TranscriptionResult.Failure(
                context.getString(R.string.transcription_audio_missing)
            )
        }

        // 有新活干就别急着释放模型
        releaseJob?.cancel()
        releaseJob = null

        withContext(Dispatchers.Default) {
            try {
                val samples = AudioDecoder.decodeToMonoFloat(audio, AudioDecoder.TARGET_SAMPLE_RATE)

                val seconds = samples.size.toDouble() / AudioDecoder.TARGET_SAMPLE_RATE
                if (seconds < MIN_AUDIO_SECONDS) {
                    return@withContext TranscriptionResult.Failure(
                        context.getString(R.string.transcription_empty_result)
                    )
                }
                if (seconds > MAX_AUDIO_SECONDS) {
                    return@withContext TranscriptionResult.Failure(
                        context.getString(
                            R.string.transcription_audio_too_long,
                            (MAX_AUDIO_SECONDS / 60).toInt(),
                        )
                    )
                }

                val recognizer = obtainRecognizer()
                    ?: return@withContext TranscriptionResult.Failure(
                        context.getString(R.string.transcription_engine_init_failed)
                    )

                val chunks = splitIntoSpeech(samples)
                if (chunks.isEmpty()) {
                    return@withContext TranscriptionResult.Failure(
                        context.getString(R.string.transcription_no_speech)
                    )
                }

                val pieces = ArrayList<String>(chunks.size)
                for (chunk in chunks) {
                    val piece = decodeChunk(recognizer, chunk)
                    if (piece.isNotBlank()) pieces.add(piece.trim())
                }

                val text = joinPieces(pieces)
                if (text.isBlank()) {
                    TranscriptionResult.Failure(context.getString(R.string.transcription_no_speech))
                } else {
                    TranscriptionResult.Success(text)
                }
            } catch (e: AudioDecoder.DecodeException) {
                TranscriptionResult.Failure(
                    e.message ?: context.getString(R.string.transcription_decode_failed)
                )
            } catch (e: Exception) {
                // 原生层抛出的东西什么样都有可能（IllegalArgumentException、
                // UnsatisfiedLinkError、OOM）。一律降级成「模型或引擎出问题」，
                // 并顺手把可能已经半坏的会话丢掉，下次重新建。
                freeNativeResources()
                TranscriptionResult.Failure(
                    context.getString(
                        R.string.transcription_engine_failed,
                        e.message ?: e::class.java.simpleName,
                    )
                )
            } finally {
                keepAliveThenRelease()
            }
        }
    }

    /** 空闲一段时间后释放原生资源。 */
    private fun keepAliveThenRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(IDLE_RELEASE_MILLIS)
            mutex.withLock { freeNativeResources() }
        }
    }

    /** 彻底释放并取消内部作用域。进程退出或用户关掉端侧引擎时调用。 */
    suspend fun shutdown() {
        releaseJob?.cancel()
        releaseJob = null
        mutex.withLock { freeNativeResources() }
        scope.cancel()
    }

    // ------------------------------------------------------------------ 识别

    /**
     * 拿到一个可用的识别器，必要时先加载模型。
     *
     * 加载失败返回 null 而不是抛异常：调用方只需要知道「现在用不了」，
     * 具体原因是模型文件被删了还是原生库加载不上，都在设置页的状态里看。
     */
    private fun obtainRecognizer(): OfflineRecognizer? {
        recognizer?.let { return it }

        val modelFile = SherpaModelCatalog.file("model.int8.onnx")?.let(models::fileOf) ?: return null
        val tokensFile = SherpaModelCatalog.file("tokens.txt")?.let(models::fileOf) ?: return null

        val created = runCatching {
            OfflineRecognizer(
                assetManager = null, // null 表示从文件系统读，不是从 assets
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(
                        sampleRate = AudioDecoder.TARGET_SAMPLE_RATE,
                        featureDim = 80,
                    ),
                    modelConfig = OfflineModelConfig(
                        // SenseVoice 不设 modelType：C++ 侧看到 senseVoice.model 非空
                        // 就会自行推导，显式设置反而可能和内部枚举对不上。
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = modelFile.absolutePath,
                            // language 留空 = 自动判断中/英/日/韩/粤，
                            // useInverseTextNormalization 保持默认开启，
                            // 让「二零二六年」这类口语数字落成「2026年」。
                        ),
                        tokens = tokensFile.absolutePath,
                        numThreads = threads(),
                        debug = false,
                        provider = "cpu",
                    ),
                ),
            )
        }.getOrNull()

        recognizer = created
        return created
    }

    /** 逐句识别。每句开一个独立的 stream，用完立刻释放。 */
    private fun decodeChunk(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, AudioDecoder.TARGET_SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text
        } finally {
            runCatching { stream.release() }
        }
    }

    // ------------------------------------------------------------------ 切句

    /**
     * 用 VAD 切出真正有人声的片段。
     *
     * VAD 不工作时（安静环境、麦克风增益偏低、阈值没命中）会退化成按固定
     * 时长切窗，而不是直接报「没检测到人声」——后者会让用户以为录音坏了，
     * 实际上音频没问题，只是模型没听出来。
     */
    private fun splitIntoSpeech(samples: FloatArray): List<FloatArray> {
        val detected = runCatching { detectWithVad(samples) }.getOrDefault(emptyList())
        if (detected.isNotEmpty()) return detected
        return fixedWindows(samples)
    }

    /** 交给 Silero VAD 逐窗判定，取出所有人声段。 */
    private fun detectWithVad(samples: FloatArray): List<FloatArray> {
        val vadModel = SherpaModelCatalog.file("silero_vad.onnx")?.let(models::fileOf) ?: return emptyList()

        val instance = vad ?: runCatching {
            Vad(
                assetManager = null,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadModel.absolutePath,
                        threshold = VAD_THRESHOLD,
                        // 离线场景下等一段静音不花额外时间（音频早就录完了），
                        // 所以把阈值放宽一点，让一句话内部的小停顿不被切开。
                        minSilenceDuration = VAD_MIN_SILENCE_SECONDS,
                        minSpeechDuration = VAD_MIN_SPEECH_SECONDS,
                        windowSize = VAD_WINDOW_SIZE,
                        maxSpeechDuration = MAX_CHUNK_SECONDS,
                    ),
                    sampleRate = AudioDecoder.TARGET_SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false,
                ),
            )
        }.getOrNull()?.also { vad = it } ?: return emptyList()

        val segments = ArrayList<FloatArray>()
        try {
            // 复用同一个实例，必须先把上一段音频的内部状态清干净
            instance.reset()

            val window = VAD_WINDOW_SIZE
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + window, samples.size)
                instance.acceptWaveform(samples.copyOfRange(offset, end))
                while (!instance.empty()) {
                    val segment = instance.front()
                    instance.pop()
                    if (segment.samples.isNotEmpty()) segments.add(segment.samples)
                }
                offset = end
            }

            // 把缓冲里最后一段没闭合的人声也吐出来
            instance.flush()
            while (!instance.empty()) {
                val segment = instance.front()
                instance.pop()
                if (segment.samples.isNotEmpty()) segments.add(segment.samples)
            }
        } finally {
            runCatching { instance.reset() }
        }
        return segments
    }

    /**
     * 固定时长切窗，作为 VAD 失效时的兜底。
     *
     * 每窗之间留 [WINDOW_OVERLAP_SAMPLES] 的重叠：切点落在词中间时，
     * 重叠能让至少一边把整句听全，代价只是这一点点重复不会都写进结果
     * （下一窗开头若与上一窗结尾文字相同会被去掉）。
     */
    private fun fixedWindows(samples: FloatArray): List<FloatArray> {
        val windowSamples = (MAX_CHUNK_SECONDS * AudioDecoder.TARGET_SAMPLE_RATE).toInt()
        if (samples.size <= windowSamples) return listOf(samples)

        val step = windowSamples - WINDOW_OVERLAP_SAMPLES
        val windows = ArrayList<FloatArray>()
        var start = 0
        while (start < samples.size) {
            val end = minOf(start + windowSamples, samples.size)
            windows.add(samples.copyOfRange(start, end))
            if (end >= samples.size) break
            start += step
        }
        return windows
    }

    // ------------------------------------------------------------------ 拼接

    /**
     * 把各段识别结果连成一段话。
     *
     * 中文里没有词间空格，直接把「你好」和「世界」用空格连起来会变成
     * 「你好 世界」，读起来很脏。所以规则是：前一段已经以标点收尾就直接接，
     * 否则补一个空格——既照顾中文，也不会把英文单词粘成一坨。
     */
    private fun joinPieces(pieces: List<String>): String {
        val builder = StringBuilder()
        pieces.forEach { piece ->
            if (builder.isEmpty()) {
                builder.append(piece)
            } else {
                val last = builder.last()
                if (last in SENTENCE_ENDINGS || last.isWhitespace()) {
                    builder.append(piece)
                } else {
                    builder.append(' ').append(piece)
                }
            }
        }
        return builder.toString().trim()
    }

    // ------------------------------------------------------------------ 资源

    private fun freeNativeResources() {
        runCatching { recognizer?.release() }
        runCatching { vad?.release() }
        recognizer = null
        vad = null
    }

    private fun threads(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS)

    private companion object {
        /** 常驻保留时长：连续录音时免去重复加载。 */
        const val IDLE_RELEASE_MILLIS = 90_000L

        /** 识别线程数上限。再多在手机上只会换来发热，不会更快。 */
        const val MAX_THREADS = 4

        /** 短于这个时长的音频不转：纯噪音，只会产出幻觉文字。 */
        const val MIN_AUDIO_SECONDS = 0.4

        /** 单次转写的时长上限，防止解出几百 MB 的浮点数组把内存打满。 */
        const val MAX_AUDIO_SECONDS = 30 * 60.0

        /** 单句送入模型的最长时长。SenseVoice 在 30 秒以内效果最好。 */
        const val MAX_CHUNK_SECONDS = 30.0f

        /** Silero VAD 的判定窗。必须与模型训练时一致，不能改。 */
        const val VAD_WINDOW_SIZE = 512

        const val VAD_THRESHOLD = 0.5f
        const val VAD_MIN_SILENCE_SECONDS = 0.5f
        const val VAD_MIN_SPEECH_SECONDS = 0.25f

        /** 兜底切窗时相邻两窗的重叠采样数（0.5 秒）。 */
        const val WINDOW_OVERLAP_SAMPLES = AudioDecoder.TARGET_SAMPLE_RATE / 2

        /** 句末标点。前一段以此收尾时不再补空格。 */
        val SENTENCE_ENDINGS = setOf(
            '。', '！', '？', '；', '，', '、', '：', '…', '—',
            '.', '!', '?', ';', ',', ':', ')', '）', '”', '"', '’', '\'',
        )
    }
}
