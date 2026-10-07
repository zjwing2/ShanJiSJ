package me.mudkip.moememos.data.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把一个网页地址变成可以直接写进笔记的正文。
 *
 * 这一层只管网络：取回 HTML 并处理重定向、超时、内容类型。怎么从 HTML 里
 * 挑出正文是 [WebArticleExtractor] 的事——那部分不碰网络，可以离线断言。
 *
 * 跑在 [Dispatchers.IO]：读流 + 解析是阻塞活，不能占主线程。
 */
@Singleton
class WebPageFetcher @Inject constructor(
    okHttpClient: OkHttpClient,
) {

    /**
     * 复用 App 的 OkHttpClient（保留 mTLS 与 cookie），只覆盖超时：
     * 默认的 10s read timeout 对某些慢站点偏紧，而 callTimeout 兜住最坏情况——
     * 用户点一次「抓取」，最多等 [CALL_TIMEOUT_SECONDS] 秒一定有结果。
     */
    private val client = okHttpClient.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(url: String): WebFetchResult = withContext(Dispatchers.IO) {
        val httpUrl = url.trim().toHttpUrlOrNull()
            ?: return@withContext WebFetchResult.Failure(WebFetchError.INVALID_URL)
        if (httpUrl.scheme != "http" && httpUrl.scheme != "https") {
            return@withContext WebFetchResult.Failure(WebFetchError.INVALID_URL)
        }

        val request = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", ACCEPT_LANGUAGE)
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (_: IOException) {
            return@withContext WebFetchResult.Failure(WebFetchError.NETWORK)
        } catch (_: IllegalArgumentException) {
            return@withContext WebFetchResult.Failure(WebFetchError.INVALID_URL)
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                return@withContext WebFetchResult.Failure(WebFetchError.HTTP)
            }

            val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
            if (contentType.isNotEmpty() && !contentType.looksLikeMarkup()) {
                return@withContext WebFetchResult.Failure(WebFetchError.NOT_HTML)
            }

            val bytes = readCapped(resp.body.byteStream(), MAX_HTML_BYTES)
            val article = WebArticleExtractor.extract(bytes, resp.request.url.toString())
                ?: return@withContext WebFetchResult.Failure(WebFetchError.EMPTY)

            WebFetchResult.Success(article)
        }
    }

    private fun readCapped(stream: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        stream.use { input ->
            while (total < limit) {
                val read = input.read(buffer, 0, minOf(buffer.size, limit - total))
                if (read <= 0) break
                out.write(buffer, 0, read)
                total += read
            }
        }
        return out.toByteArray()
    }

    private fun String.looksLikeMarkup(): Boolean =
        contains("html") || contains("text/plain") || contains("xml")

    companion object {
        /**
         * 桌面 Chrome 的 UA。
         *
         * 用移动端 UA 会拿到一堆站点为手机准备的「简化页」——正文被折叠进 JS 里、
         * 甚至直接跳转到 App 下载页；桌面 UA 反而更容易拿到完整正文。
         */
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        private const val ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8"

        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val READ_TIMEOUT_SECONDS = 20L
        private const val CALL_TIMEOUT_SECONDS = 30L

        /** 一份 HTML 最多读这么多字节，防止一个下载链接把内存吃光。 */
        private const val MAX_HTML_BYTES = 4 * 1024 * 1024
    }
}
