package me.mudkip.moememos.data.transcription

import java.io.File

/**
 * 语音转写引擎的公共契约。
 *
 * 只有两个实现：[OnDeviceTranscriber]（本地模型，音频不出设备）与
 * [CloudTranscriber]（远端 API）。调用方（[TranscriptionManager]）不关心是谁在转，
 * 只关心「此刻能不能转」和「转出来什么」。
 *
 * 刻意不做流式：闪念笔记的录音通常几十秒到几分钟，一次性转完再落文字，
 * 比边说边改正文要少很多状态机，也不会和用户同时在编辑的正文打架。
 */
interface TranscriptionEngine {

    /** 引擎标识，用于日志。 */
    val id: String

    /**
     * 此刻是否可用。不可用时要把原因带出来给用户看，
     * 比如「模型还没下载」「还没填 API Key」——不能让用户对着灰按钮猜。
     */
    suspend fun availability(): TranscriptionAvailability

    /**
     * 转写一个音频文件。
     *
     * 实现必须自行兜住所有异常并转成 [TranscriptionResult.Failure]：
     * 转写失败绝不能影响录音本身已经保存成功这个事实。
     */
    suspend fun transcribe(audio: File): TranscriptionResult
}

/** 引擎可用性。 */
sealed interface TranscriptionAvailability {

    /** 可以转。 */
    data object Ready : TranscriptionAvailability

    /**
     * 暂时不能转。
     *
     * @param reason 已本地化的原因，直接展示给用户。
     * @param needsDownload 端侧模型缺失时为 true，UI 据此把按钮换成「下载模型」。
     */
    data class Unavailable(
        val reason: String,
        val needsDownload: Boolean = false,
    ) : TranscriptionAvailability
}

/** 一次转写的结果。 */
sealed interface TranscriptionResult {

    /**
     * 成功。
     *
     * @param text 转写出的纯文本。**不含**任何时间戳包装——那属于展示层的事，
     *   由调用方决定怎么追加进正文。
     */
    data class Success(val text: String) : TranscriptionResult

    /** 失败，[message] 可直接展示。 */
    data class Failure(val message: String) : TranscriptionResult
}

/** 转写结果为空时的统一判定：全是空白就等于没转出东西。 */
fun TranscriptionResult.Success.isBlank(): Boolean = text.isBlank()
