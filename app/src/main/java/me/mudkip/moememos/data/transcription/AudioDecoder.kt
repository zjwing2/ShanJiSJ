package me.mudkip.moememos.data.transcription

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/**
 * 把录音文件解成模型要的单声道浮点 PCM。
 *
 * ## 为什么需要这一层
 *
 * sherpa-onnx 的接口只认 `FloatArray`（[-1, 1] 的 PCM 采样），而我们的录音是
 * AAC 装在 MPEG-4 里（`.m4a`）。中间这段解码必须自己做：没有现成的库能同时
 * 覆盖「系统自带、零依赖、能读 m4a、能出 float」。
 *
 * ## 为什么不用改写录音格式
 *
 * 把录音改成 WAV 可以省掉这一层（模型直接读），但代价是体积涨 8 倍——
 * 16 kHz 单声道 WAV 是 32 KB/s，AAC 只有 4 KB/s。这些音频要进 OneDrive
 * 做同步，为了省一次解码把同步体积翻八倍不划算。所以音频保持 AAC，
 * 转写时**按需解码**，解出来的 PCM 只活在内存里。
 *
 * ## 为什么不需要重采样
 *
 * 录音时就固定了 16 kHz 单声道，正好是 SenseVoice 要的输入规格。这里的
 * 重采样分支只是防御性的：万一用户拿别处来的音频（比如从微信导出的
 * 44.1 kHz 录音）走同一条链路，也不至于喂出乱码文本。
 */
object AudioDecoder {

    /** 模型要求的采样率。 */
    const val TARGET_SAMPLE_RATE = 16_000

    /** `MediaFormat` 没给出 PCM 编码时的默认值。 */
    private const val ENCODING_PCM_16BIT = 2

    /** `MediaFormat.KEY_PCM_ENCODING` 的浮点取值（API 24+）。 */
    private const val ENCODING_PCM_FLOAT = 4

    private const val TIMEOUT_US = 10_000L

    /** 解码失败。[message] 可直接展示给用户。 */
    class DecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * 解码成单声道浮点 PCM。
     *
     * @param targetSampleRate 目标采样率，默认 16 kHz。
     * @return [-1, 1] 区间的采样。调用方自行按 [targetSampleRate] 换算时长。
     * @throws DecodeException 文件不可读、无音轨、或解码器拒绝该格式。
     */
    fun decodeToMonoFloat(
        file: File,
        targetSampleRate: Int = TARGET_SAMPLE_RATE,
    ): FloatArray {
        if (!file.isFile || file.length() == 0L) {
            throw DecodeException("音频文件不存在或为空")
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw DecodeException("这个文件里没有音轨")

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw DecodeException("音轨缺少编码信息")

            var channelCount = inputFormat.optInt(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var sourceSampleRate = inputFormat.optInt(MediaFormat.KEY_SAMPLE_RATE, targetSampleRate)

            // 先落到不可变的局部变量上：`codec` 是可变字段，直接对它做非空调用
            // 会撞上 Kotlin 的可空智能转换限制，多写一行的代价远小于调试这个。
            val decoder = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw DecodeException("系统解码器不支持 $mime", e)
            }
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val interleaved = ArrayList<Float>(sourceSampleRate * 8)
            val info = MediaCodec.BufferInfo()
            var outputIsFloat = false
            var sawInputEos = false
            var sawOutputEos = false

            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inIndex)
                        val size = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEos = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = decoder.outputFormat
                        channelCount = outFormat.optInt(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                        sourceSampleRate = outFormat.optInt(
                            MediaFormat.KEY_SAMPLE_RATE, sourceSampleRate
                        )
                        outputIsFloat = outFormat.optInt(
                            MediaFormat.KEY_PCM_ENCODING, ENCODING_PCM_16BIT
                        ) == ENCODING_PCM_FLOAT
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outIndex >= 0) {
                        val buffer = decoder.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            readSamples(buffer, channelCount, outputIsFloat, interleaved)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEos = true
                        }
                    }
                }
            }

            if (interleaved.isEmpty()) {
                throw DecodeException("解码后没有任何音频数据")
            }

            val mono = downmixToMono(interleaved, channelCount)
            return if (sourceSampleRate == targetSampleRate) {
                mono
            } else {
                resample(mono, sourceSampleRate, targetSampleRate)
            }
        } catch (e: DecodeException) {
            throw e
        } catch (e: Exception) {
            throw DecodeException("音频解码失败：${e.message ?: e::class.java.simpleName}", e)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** 把一块输出缓冲读成浮点采样，追加到 [out]（仍是交错态）。 */
    private fun readSamples(
        buffer: java.nio.ByteBuffer,
        channelCount: Int,
        isFloat: Boolean,
        out: ArrayList<Float>,
    ) {
        // MediaCodec 的输出缓冲是 native 字节序；不显式设置会在大端机器上读错。
        buffer.order(ByteOrder.nativeOrder())
        val bytesPerSample = if (isFloat) 4 else 2
        val frames = buffer.remaining() / (bytesPerSample * channelCount.coerceAtLeast(1))
        out.ensureCapacity(out.size + frames * channelCount)
        repeat(frames * channelCount) {
            out.add(if (isFloat) buffer.float else buffer.short / 32768f)
        }
    }

    /** 多声道取平均折成单声道；本来就是单声道则原样返回。 */
    private fun downmixToMono(interleaved: List<Float>, channelCount: Int): FloatArray {
        if (channelCount <= 1) return FloatArray(interleaved.size) { interleaved[it] }
        val frames = interleaved.size / channelCount
        val mono = FloatArray(frames)
        var sum: Float
        for (f in 0 until frames) {
            sum = 0f
            for (c in 0 until channelCount) {
                sum += interleaved[f * channelCount + c]
            }
            mono[f] = sum / channelCount
        }
        return mono
    }

    /**
     * 线性插值重采样。够用就好：语音识别对重采样质量不敏感，
     * 而带通滤波那一套在这里只会增加出错面。
     */
    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input
        val ratio = to.toDouble() / from
        val outLength = (input.size * ratio).toInt().coerceAtLeast(1)
        val output = FloatArray(outLength)
        for (i in 0 until outLength) {
            val pos = i / ratio
            val left = pos.toInt()
            val right = (left + 1).coerceAtMost(input.size - 1)
            val frac = (pos - left).toFloat()
            output[i] = input[left] * (1f - frac) + input[right] * frac
        }
        return output
    }

    /** [MediaFormat] 没有对应键时的静默取值。 */
    private fun MediaFormat.optInt(key: String, fallback: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback
}
