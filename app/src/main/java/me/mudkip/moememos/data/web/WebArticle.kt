package me.mudkip.moememos.data.web

/**
 * 抓下来的网页正文。
 *
 * [url] 是**跟随重定向之后**的最终地址：短链、移动端跳转都会落在别处，
 * 记最终地址才打开得回去。[markdown] 已经是可以直接塞进笔记的 Markdown，
 * 图片只保留远程链接（`![alt](https://…)`），不下载、不落盘。
 */
data class WebArticle(
    val url: String,
    val title: String,
    val byline: String?,
    val markdown: String,
    val truncated: Boolean,

    /**
     * true 表示**正文没抓到**，[markdown] 里装的是页面自带的摘要（og:description 之类）。
     *
     * 这时候笔记里必须写明「只存了摘要」：不标注的话，用户会把这段几十字的摘要
     * 当成抓下来的全文，回头发现少了一大截，只会觉得这个功能是坏的。
     */
    val summaryOnly: Boolean = false,
)

/** 抓取结果。失败用枚举而不是异常：每一种失败在界面上都是一句人话，不需要堆栈。 */
sealed interface WebFetchResult {
    data class Success(val article: WebArticle) : WebFetchResult
    data class Failure(val reason: WebFetchError) : WebFetchResult
}

enum class WebFetchError {
    /** 不是一个能打开的 http/https 地址。 */
    INVALID_URL,

    /** 连不上：DNS、断网、超时、TLS 失败。 */
    NETWORK,

    /** 连上了但对方不满意这次请求（404/403/5xx 等）。 */
    HTTP,

    /** 地址背后不是网页（PDF、图片、压缩包）。 */
    NOT_HTML,

    /** 打开是打开了，但一个字的正文都没抽出来。 */
    EMPTY,
}
