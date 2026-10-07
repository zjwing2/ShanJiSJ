package me.mudkip.moememos.data.transcription

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.mudkip.moememos.R
import me.mudkip.moememos.data.local.TranscriptionPrefs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 云端转写引擎，走 OpenAI 兼容的多段表单接口。
 *
 * 之所以认这个格式而不是某一家私有协议：阿里百炼、硅基流动、讯飞星火、
 * Groq、OpenAI 自己都提供 `POST /v1/audio/transcriptions`，请求体是
 * `file + model` 两个字段、响应体是 `{"text": "..."}`。于是用户只要在设置页
 * 填「端点 + 密钥 + 模型名」就能换服务商，不需要我们为每家写适配器。
 *
 * 隐私边界要说清楚：走到这里音频就离开设备了。所以引擎是**用户显式选的**，
 * 默认值是端侧。设置页也必须把这句话写在界面上。
 */
@Singleton
class CloudTranscriber @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : TranscriptionEngine {

    override val id: String = "cloud"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // 转写是低频重活：一次几分钟的录音，服务端排队 + 解码可能远超默认 10 秒。
    // 单独建客户端而不是复用全局的——全局那套超时是按笔记同步调的，太短。
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    override suspend fun availability(): TranscriptionAvailability =
        if (!TranscriptionPrefs.isCloudConfigured(context)) {
            TranscriptionAvailability.Unavailable(
                reason = context.getString(R.string.transcription_cloud_not_configured)
            )
        } else {
            TranscriptionAvailability.Ready
        }

    override suspend fun transcribe(audio: File): TranscriptionResult = withContext(Dispatchers.IO) {
        if (!audio.isFile || audio.length() == 0L) {
            return@withContext TranscriptionResult.Failure(
                context.getString(R.string.transcription_audio_missing)
            )
        }

        val endpoint = TranscriptionPrefs.cloudEndpoint(context)
        val apiKey = TranscriptionPrefs.cloudApiKey(context)
        val model = TranscriptionPrefs.cloudModel(context)

        if (endpoint.isBlank() || apiKey.isBlank()) {
            return@withContext TranscriptionResult.Failure(
                context.getString(R.string.transcription_cloud_not_configured)
            )
        }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            // 录音容器是 MPEG-4 里的 AAC，MIME 用 audio/mp4；
            // 转写服务普遍按扩展名/MIME 决定是否要转码，给错会被拒。
            .addFormDataPart("file", audio.name, audio.asRequestBody(AUDIO_MIME.toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .build()

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val payload = response.body.string()
                if (!response.isSuccessful) {
                    // 把服务端返回的正文截一段带出来——多数服务商会在这里写清
                    // 「密钥无效」「模型不存在」「文件太大」，比单看状态码有用得多。
                    val detail = payload.take(MAX_ERROR_CHARS).ifBlank { response.message }
                    return@withContext TranscriptionResult.Failure(
                        context.getString(R.string.transcription_cloud_failed, response.code, detail)
                    )
                }
                val text = runCatching {
                    json.decodeFromString<TranscriptionPayload>(payload).text
                }.getOrNull()
                    ?: return@withContext TranscriptionResult.Failure(
                        context.getString(R.string.transcription_response_unreadable)
                    )

                if (text.isBlank()) {
                    TranscriptionResult.Failure(context.getString(R.string.transcription_empty_result))
                } else {
                    TranscriptionResult.Success(text.trim())
                }
            }
        } catch (e: Exception) {
            TranscriptionResult.Failure(
                context.getString(R.string.transcription_cloud_failed, -1, e.message ?: e::class.java.simpleName)
            )
        }
    }

    /** OpenAI 兼容接口的最小响应形状；多余字段一律忽略。 */
    @Serializable
    private data class TranscriptionPayload(val text: String = "")

    companion object {
        private const val AUDIO_MIME = "audio/mp4"
        private const val MAX_ERROR_CHARS = 300
    }
}
