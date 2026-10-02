package dev.frost819.newbv.bangumiapi.http

import dev.frost819.newbv.bangumiapi.entity.BangumiSubjectPage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

/**
 * Bangumi（bgm.tv）HTTP 接口客户端。
 *
 * 只封装 v0 列表端点 `GET /v0/subjects`。**不使用** p1 通路作为默认实现：
 * p1 列表项**没有 `tags` 字段**，且 `total` 语义是页数不是条数，
 * 一旦切过去会导致约 33% 的卡片筛不出题材词。p1 仅作降级通路（后续阶段再补）。
 *
 * 该端点**裸请求即可**，不校验 UA、不需要鉴权；这里仍显式带上 UA，
 * 以免未来被服务端按 UA 风控。
 *
 * ## 多候选源兜底（照搬 PiliPlus-Bangumi 的 `lib/http/bangumi.dart`）
 *
 * 候选顺序：**用户首选源 → 官方 → 社区反代**。官方被墙/DNS 污染时自动切反代，
 * 单个源偶发 DNS 抖动则同源退避重试，避免「一次抖动就整页报错」。
 *
 * 实测记录（2026-09-26）：旧的 `api.bangumi.lol` 已停服（连根路径都 404），
 * **勿再启用**；现用两个反代返回字段与官方逐字一致。
 *
 * @param client 可注入的 Ktor 客户端（单测用 MockEngine 注入）。
 * @param preferredBaseUrl 首选接口地址的提供者（设置页里的选择，空串 = 官方优先）。
 */
class BangumiHttpApi(
    private val client: HttpClient = createDefaultClient(),
    private val preferredBaseUrl: () -> String = { BASE_URL },
) {
    /**
     * 上次成功的接口地址。
     *
     * 官方挂掉时，同一月的后续翻页不必再从死源重试一遍 —— 直接沿用上次成功的源。
     */
    @Volatile
    private var lastGoodBaseUrl: String? = null

    /**
     * 按「年 + 月 + 分类」拉取一页条目。
     *
     * 失败时按「同源退避重试 → 换下一个候选源」推进；只有**可重试的错误**
     * （连接类 / 5xx / 404 / 408 / 429）才继续尝试，参数错误这类 4xx 立即抛出。
     *
     * @param type 条目大类，动画固定 [dev.frost819.newbv.bangumiapi.entity.BANGUMI_TYPE_ANIME]。
     * @param cat 动画子类，见 [dev.frost819.newbv.bangumiapi.entity.BangumiCategory]。
     * @param year 年份（4 位）。
     * @param month 月份（1~12）。
     * @param limit 单页条数，**上限 100**（传 200 会 HTTP 400）。
     * @param offset 偏移量。
     * @return 分页响应；[BangumiSubjectPage.total] 是总条数而非总页数。
     * @throws io.ktor.client.plugins.ClientRequestException 服务端返回**不可重试**的 4xx。
     * @throws java.io.IOException 所有候选源都失败。
     */
    suspend fun getSubjects(
        type: Int,
        cat: Int,
        year: Int,
        month: Int,
        limit: Int,
        offset: Int,
    ): BangumiSubjectPage = fetchWithFallback { base ->
        client
            .get("$base/v0/subjects") {
                parameter("type", type)
                parameter("cat", cat)
                parameter("year", year)
                parameter("month", month)
                parameter("limit", limit)
                parameter("offset", offset)
            }.body()
    }

    /**
     * 依次尝试候选地址，每源最多 [MAX_ATTEMPTS_PER_SOURCE] 次（首次 + 退避重试）。
     *
     * @param request 用给定 base 发一次请求。
     * @return 首个成功的结果。
     * @throws Exception 所有候选源都失败时抛出最后一个可重试错误。
     */
    private suspend fun <T> fetchWithFallback(request: suspend (String) -> T): T {
        var lastError: Throwable? = null
        for (base in candidateBaseUrls()) {
            for (attempt in 0 until MAX_ATTEMPTS_PER_SOURCE) {
                try {
                    val result = request(base)
                    lastGoodBaseUrl = base
                    return result
                } catch (cancelled: CancellationException) {
                    // 协程取消不是网络故障，必须原样抛出，否则会让调用方的取消语义失效
                    throw cancelled
                } catch (error: Throwable) {
                    if (!isRetryable(error)) throw error
                    lastError = error
                    if (attempt + 1 < MAX_ATTEMPTS_PER_SOURCE) {
                        delay(RETRY_BACKOFF_MS[attempt])
                    }
                }
            }
        }
        throw lastError ?: IOException("Bangumi 网络请求失败：没有可用的接口地址")
    }

    /**
     * 候选地址列表：上次成功的源 → 用户首选 → 官方 → 反代（去重）。
     *
     * 把 [lastGoodBaseUrl] 放在最前，是为了让「已切到反代」的状态在整月翻页中保持，
     * 不必每一页都重新在死源上重试三次。
     */
    private fun candidateBaseUrls(): List<String> {
        val preferred = preferredBaseUrl().trim().trimEnd('/')
        return buildList {
            lastGoodBaseUrl?.let(::add)
            if (preferred.isNotEmpty()) add(preferred)
            add(BASE_URL)
            addAll(MIRROR_BASE_URLS)
        }.distinct()
    }

    /**
     * 该错误是否值得重试 / 换源。
     *
     * - 连接类（含 DNS 解析失败）与超时 ⇒ 是，多为瞬时抖动
     * - HTTP 5xx / 404 / 429 / 408 ⇒ 是，反代挂掉的典型响应
     * - 其余 4xx（如 limit 超限）⇒ 否，换源也一样会被拒
     *
     * @param error 捕获到的异常。
     * @return 是否继续尝试下一个源。
     */
    private fun isRetryable(error: Throwable): Boolean =
        when (error) {
            is ServerResponseException -> true
            is ClientRequestException -> error.response.status.value in RETRYABLE_HTTP_CODES
            is IOException -> true
            else -> false
        }

    companion object {
        /** Bangumi v0 API 官方根地址。 */
        const val BASE_URL: String = "https://api.bgm.tv"

        /**
         * 社区反代（非官方，官方被墙/DNS 污染时备用）。
         *
         * 两者返回字段与官方**逐字一致**，封面 URL 会被改写成各自图片域名，
         * 但**路径结构不变**（`/r/400/pic/cover/...`），故封面地址无需特殊处理。
         *
         * ⚠️ 旧的 `https://api.bangumi.lol` 已于 2026-09 停服，**勿再启用**。
         */
        val MIRROR_BASE_URLS: List<String> =
            listOf(
                "https://bgm.retr0.xyz/8d7db5cae",
                "https://bgmapi.anibt.net",
            )

        /** 单源最多尝试次数（首次 + 2 次重试）。 */
        const val MAX_ATTEMPTS_PER_SOURCE: Int = 3

        /** 同源重试的退避间隔（ms）。 */
        private val RETRY_BACKOFF_MS: List<Long> = listOf(300L, 800L)

        /** 换源有意义的状态码。 */
        private val RETRYABLE_HTTP_CODES: Set<Int> = setOf(404, 408, 429, 500, 502, 503, 504)

        /** 请求 UA（用于服务端侧识别，不影响鉴权）。 */
        const val USER_AGENT: String = "xmbl4399/newBV-Bangumi/0.1 (https://github.com/xmbl4399/newBV)"

        /** 单请求超时（ms）。v0 单月响应可达 100 KB 级，实测 3~9 秒。 */
        private const val REQUEST_TIMEOUT_MS = 20_000L

        /** 连接超时（ms）。 */
        private const val CONNECT_TIMEOUT_MS = 10_000L

        /**
         * 响应解码配置。
         *
         * 取舍：
         * - `ignoreUnknownKeys`：接口会新增字段，不能让它把解析打挂；
         * - `coerceInputValues`：把 `"eps": null` 这类**显式 null** 收敛成属性默认值，
         *   否则非空类型字段遇到 null 会抛序列化异常；
         * - `isLenient`：容忍数字/字符串的松散写法。
         *
         * 未设置 `explicitNulls = false`：列表项字段缺失靠默认值兜底即可，
         * 不需要牺牲编码侧的 null 语义。
         *
         * `internal` 供同模块单测复用，保证测试解码行为与生产一致。
         */
        internal val JSON_CONFIG: Json =
            Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                isLenient = true
            }

        /**
         * 创建默认 Ktor 客户端。
         *
         * @return 可用于生产请求的客户端。
         */
        fun createDefaultClient(): HttpClient =
            HttpClient(OkHttp) {
                expectSuccess = true
                install(ContentNegotiation) {
                    json(JSON_CONFIG)
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                    connectTimeoutMillis = CONNECT_TIMEOUT_MS
                    socketTimeoutMillis = REQUEST_TIMEOUT_MS
                }
                defaultRequest {
                    header(HttpHeaders.UserAgent, USER_AGENT)
                }
            }
    }
}
