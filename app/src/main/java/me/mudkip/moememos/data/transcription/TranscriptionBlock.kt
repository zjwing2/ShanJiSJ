package me.mudkip.moememos.data.transcription

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 转写文字落进正文时的排版。
 *
 * 单独成一个纯函数而不是写在 [TranscriptionManager] 里：这是整个功能里
 * 用户唯一**直接看到**的产出格式，也是最容易被后续改动碰坏的地方
 * （比如有人顺手把时间戳去掉、或者把换行数改了一个），值得被断言固定下来。
 *
 * 形态（[label] 取当前语言的「语音」）：
 * ```
 *
 * [语音 22:15]
 * 明天下午三点跟张工对一下接口文档。
 * ```
 *
 * 开头空一行是为了和上面已有的正文分开；即使上面本来空着，也只多出一个空行，
 * Markdown 渲染下看不出来。
 *
 * [label] 由调用方传入而不是写死在这里，是为了让它跟着语言走——
 * 纯函数不碰资源，资源由认识 Context 的那一层负责。
 */
internal fun buildTranscriptionBlock(
    label: String,
    recordingTime: LocalDateTime,
    text: String,
): String = "\n\n[$label ${CLOCK.format(recordingTime)}]\n${text.trim()}"

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
