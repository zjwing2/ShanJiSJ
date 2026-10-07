package me.mudkip.moememos.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * 语音转写的持久化设置。
 *
 * 与 [FolderMirrorPrefs] 同样的取舍：不碰 DataStore / Room / Settings 模型，
 * 用独立的 SharedPreferences 让「输入页 ViewModel、设置页 UI、转写调度器」
 * 三方共享同一份状态而互不依赖。
 *
 * 引擎三态而不是布尔开关：`OFF` 表示完全不转写（录音照常，只是不产生文字），
 * 这样关掉转写不需要清掉任何已配置的模型或密钥，重新打开即恢复。
 */
object TranscriptionPrefs {

    /** 转写引擎。`OFF` 时录音功能完全不受影响，只是不再产出文字。 */
    enum class Engine {
        /** 不转写。 */
        OFF,

        /** 端侧离线模型（sherpa-onnx + SenseVoice），音频不出设备。 */
        ON_DEVICE,

        /** 云端 API，音频会上传。 */
        CLOUD,
    }

    private const val FILE = "moememos_transcription"
    private const val KEY_ENGINE = "engine"
    private const val KEY_CLOUD_ENDPOINT = "cloud_endpoint"
    private const val KEY_CLOUD_API_KEY = "cloud_api_key"
    private const val KEY_CLOUD_MODEL = "cloud_model"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun engine(context: Context): Engine {
        val raw = prefs(context).getString(KEY_ENGINE, null) ?: return Engine.OFF
        return runCatching { Engine.valueOf(raw) }.getOrDefault(Engine.OFF)
    }

    fun setEngine(context: Context, engine: Engine) {
        prefs(context).edit().putString(KEY_ENGINE, engine.name).apply()
    }

    /** 是否处于「已选定引擎」的生效状态。所有转写入口都以此为准。 */
    fun isActive(context: Context): Boolean = engine(context) != Engine.OFF

    fun cloudEndpoint(context: Context): String =
        prefs(context).getString(KEY_CLOUD_ENDPOINT, DEFAULT_CLOUD_ENDPOINT).orEmpty()

    fun cloudApiKey(context: Context): String =
        prefs(context).getString(KEY_CLOUD_API_KEY, "").orEmpty()

    fun cloudModel(context: Context): String =
        prefs(context).getString(KEY_CLOUD_MODEL, DEFAULT_CLOUD_MODEL).orEmpty()

    fun setCloudConfig(context: Context, endpoint: String, apiKey: String, model: String) {
        prefs(context).edit()
            .putString(KEY_CLOUD_ENDPOINT, endpoint.trim())
            .putString(KEY_CLOUD_API_KEY, apiKey.trim())
            .putString(KEY_CLOUD_MODEL, model.trim())
            .apply()
    }

    /** 云端配置是否完整到可以发请求。 */
    fun isCloudConfigured(context: Context): Boolean =
        cloudEndpoint(context).isNotBlank() && cloudApiKey(context).isNotBlank()

    // 这是个 standalone object，成员本身就是单例，不需要再套一层 companion。
    /** OpenAI 兼容的多段表单转写端点，阿里百炼 / 硅基流动 / 讯飞等大多兼容。 */
    const val DEFAULT_CLOUD_ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"

    const val DEFAULT_CLOUD_MODEL = "whisper-1"
}
