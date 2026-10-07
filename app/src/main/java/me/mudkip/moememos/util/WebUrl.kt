package me.mudkip.moememos.util

/**
 * 从一段自由文本里挑出第一个网页地址。
 *
 * 用在输入页：用户把链接粘进正文，界面据此提示「要不要把正文抓下来」。
 * 正则刻意排除空白、引号和中文标点——中文写作里链接后面常常**紧跟**标点
 * （「…详见 https://example.com/a。 」），不排除的话标点会被吞进地址里。
 */
private val WEB_URL_PATTERN = Regex(
    pattern = """https?://[^\s<>"'“”‘’（）()\[\]【】{}]+""",
    option = RegexOption.IGNORE_CASE,
)

/** 地址末尾常见的中英文标点，属于句子而不属于链接。 */
private const val TRAILING_PUNCTUATION = ".,;:!?、，。；：！？）)】」』"

fun findFirstWebUrl(text: String): String? {
    val match = WEB_URL_PATTERN.find(text)?.value ?: return null
    // 域名字符后面跟着句号是最常见的情况（英文句末），一律去掉；
    // 去掉后如果连域名都不剩了，就当没匹配到。
    val trimmed = match.trimEnd { it in TRAILING_PUNCTUATION }
    return trimmed.takeIf { it.length > "https://".length }
}
