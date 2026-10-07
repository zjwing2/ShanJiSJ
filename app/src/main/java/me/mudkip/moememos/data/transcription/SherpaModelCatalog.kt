package me.mudkip.moememos.data.transcription

/**
 * 端侧转写所需的模型文件清单。
 *
 * ## 为什么把 URL 和哈希写死在代码里
 *
 * 这三个文件是 App 运行期从网上下载的**可执行模型数据**，下载源一旦被替换，
 * 拿到的东西就会直接喂进 ONNX Runtime 执行。所以这里不接受「用户自定义下载地址」，
 * 而是把地址、字节数、SHA-256 三项固化，下载后逐字节校验。这也是为什么
 * 校验值来自 HuggingFace 仓库自身的 LFS 指针（`oid sha256:...`），
 * 而不是我们自己算一遍——那样才能证明「下的就是官方发布的那份」。
 *
 * ## 体积的取舍
 *
 * 三个文件合计约 228.8 MB，全部**不进 APK**，只在使用前下载。APK 本体里
 * 只有约 30 MB 的原生库。这样安装包保持轻量，而模型是否要占这 228 MB，
 * 决定权在用户手上（下载前会明确告知体积）。
 */
object SherpaModelCatalog {

    /** 官方模型仓库，镜像与主源都指向同一份文件。 */
    private const val HF_REPO = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"

    /** ModelScope 上的同一份文件（国内直连更快，作为回退源）。 */
    private const val MS_REPO = "poloniumrock/SenseVoiceSmallOnnx"

    /** 判断「这份数据是不是我们要的」的唯一依据。 */
    private const val SENSE_VOICE_SHA = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"
    // tokens.txt 不是 LFS 文件，HF 的 raw 接口直接返回内容，没有 oid 指针可查。
    // 此值取自 hf-mirror 与 ModelScope 两源实下载文件的 SHA-256（两源字节级一致）。
    private const val TOKENS_SHA = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"
    private const val SILERO_SHA = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"

    /**
     * SenseVoice Small（int8 量化）。
     *
     * 中/英/日/韩/粤五语自动识别，自带标点恢复与数字规范化（ITN），
     * 不需要再挂一个标点模型。选 int8 而不是 fp32 版本：228 MB / 894 MB 的差距，
     * 而在手机端语音识别这个任务上量化带来的精度损失几乎听不出来。
     */
    private val senseVoice = RemoteModelFile(
        fileName = "model.int8.onnx",
        sizeBytes = 239_233_841L,
        sha256 = SENSE_VOICE_SHA,
        urls = listOf(
            "https://hf-mirror.com/$HF_REPO/resolve/main/model.int8.onnx",
            "https://www.modelscope.cn/models/$MS_REPO/resolve/master/model.int8.onnx",
        ),
    )

    /** 词表，与上面的模型严格配对。 */
    private val tokens = RemoteModelFile(
        fileName = "tokens.txt",
        sizeBytes = 315_894L,
        sha256 = TOKENS_SHA,
        urls = listOf(
            "https://hf-mirror.com/$HF_REPO/resolve/main/tokens.txt",
            "https://www.modelscope.cn/models/$MS_REPO/resolve/master/tokens.txt",
        ),
    )

    /**
     * Silero VAD，负责切出真正有人声的片段。
     *
     * 有它才能做两件事：一是过滤纯静音（不然静音段也会被硬转出一堆幻觉文字），
     * 二是把长录音切成 SenseVoice 擅长的短句（它是非流式模型，整段五分钟
     * 一起喂进去会又慢又不准）。
     */
    private val sileroVad = RemoteModelFile(
        fileName = "silero_vad.onnx",
        sizeBytes = 643_854L,
        sha256 = SILERO_SHA,
        urls = listOf(
            "https://gh-proxy.com/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            "https://ghfast.top/https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
        ),
    )

    /** 必须全部就位才允许转写。顺序即下载顺序：先小后大，让进度条早点动起来。 */
    val files: List<RemoteModelFile> = listOf(sileroVad, tokens, senseVoice)

    /** 全部文件的字节数合计，用于在下载前告知用户会占用多少空间。 */
    val totalBytes: Long = files.sumOf { it.sizeBytes }

    /** 按文件名找回条目。 */
    fun file(name: String): RemoteModelFile? = files.firstOrNull { it.fileName == name }
}

/**
 * 一个需要下载的模型文件。
 *
 * @param urls 按优先级排列的下载源，前一个失败自动换下一个。
 *   每个源都必须提供同一份字节（由 [sha256] 兜底）。
 */
data class RemoteModelFile(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val urls: List<String>,
) {
    /** 人类可读的体积，用于界面提示。 */
    val sizeMb: Long get() = (sizeBytes + 512 * 1024) / (1024 * 1024)
}
