package dev.frost819.newbv.bangumiapi.http

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * [BangumiHttpApi] 的单元测试（MockEngine，不触网）。
 *
 * 验证请求参数拼装、响应解码容错（`meta_tags` 重复、`eps` 显式 null、未知字段）与错误透出。
 */
class BangumiHttpApiTest {
    /** 单月响应样例，含 `meta_tags` 重复 bug 与 `eps: null` 边界。 */
    private val monthResponseJson =
        """
        {
          "total": 2,
          "limit": 100,
          "offset": 0,
          "data": [
            {
              "id": 443446,
              "name": "地獄楽 第二季",
              "name_cn": "地狱乐 第二季",
              "date": "2026-01-11",
              "eps": 12,
              "images": { "large": "L", "common": "C", "medium": "M", "small": "S", "grid": "G" },
              "rating": { "score": 6.8, "rank": 2345, "total": 1203 },
              "tags": [ { "name": "战斗", "count": 524, "total_count": 38921 } ],
              "meta_tags": ["TV", "TV", "日本", "日本", "奇幻"],
              "unknown_future_field": { "a": 1 }
            },
            {
              "id": 999,
              "name": "Unrated Show",
              "date": "",
              "eps": null,
              "images": {},
              "rating": {}
            }
          ]
        }
        """.trimIndent()

    private fun apiWith(
        expectedStatus: HttpStatusCode = HttpStatusCode.OK,
        body: String = monthResponseJson,
        onRequest: (HttpRequestData) -> Unit = {},
    ): BangumiHttpApi {
        val engine =
            MockEngine { request ->
                onRequest(request)
                respond(
                    content = ByteReadChannel(body),
                    status = expectedStatus,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        val client =
            HttpClient(engine) {
                expectSuccess = true
                install(ContentNegotiation) { json(BangumiHttpApi.JSON_CONFIG) }
            }
        return BangumiHttpApi(client)
    }

    @Test
    fun `getSubjects sends v0 list query parameters`() =
        runTest {
            // Given
            var captured: HttpRequestData? = null
            val api = apiWith(onRequest = { captured = it })

            // When
            api.getSubjects(type = 2, cat = 1, year = 2026, month = 1, limit = 100, offset = 0)

            // Then
            val request = requireNotNull(captured)
            assertThat(request.url.encodedPath).isEqualTo("/v0/subjects")
            assertThat(request.url.host).isEqualTo("api.bgm.tv")
            assertThat(request.url.parameters["type"]).isEqualTo("2")
            assertThat(request.url.parameters["cat"]).isEqualTo("1")
            assertThat(request.url.parameters["year"]).isEqualTo("2026")
            assertThat(request.url.parameters["month"]).isEqualTo("1")
            assertThat(request.url.parameters["limit"]).isEqualTo("100")
            assertThat(request.url.parameters["offset"]).isEqualTo("0")
        }

    @Test
    fun `getSubjects decodes list response and keeps duplicated meta tags raw`() =
        runTest {
            val api = apiWith()

            val page = api.getSubjects(type = 2, cat = 1, year = 2026, month = 1, limit = 100, offset = 0)

            assertThat(page.total).isEqualTo(2)
            assertThat(page.data).hasSize(2)
            val first = page.data[0]
            assertThat(first.id).isEqualTo(443446)
            assertThat(first.nameCn).isEqualTo("地狱乐 第二季")
            assertThat(first.images.common).isEqualTo("C")
            assertThat(first.rating.total).isEqualTo(1203)
            // 原始脏数据原样保留，去重交给 TagPicker
            assertThat(first.metaTags).containsExactly("TV", "TV", "日本", "日本", "奇幻")
        }

    @Test
    fun `getSubjects coerces explicit null eps and missing rating to defaults`() =
        runTest {
            val api = apiWith()

            val second = api.getSubjects(type = 2, cat = 1, year = 2026, month = 1, limit = 100, offset = 0).data[1]

            assertThat(second.eps).isEqualTo(0)
            assertThat(second.rating.score).isEqualTo(0.0)
            assertThat(second.date).isEmpty()
            assertThat(second.tags).isEmpty()
        }

    @Test
    fun `getSubjects throws on http error`() =
        runTest {
            // Given：limit 超限时服务端返回 400
            val api = apiWith(expectedStatus = HttpStatusCode.BadRequest, body = """{"message":"limit too large"}""")

            // When / Then
            val error =
                runCatching {
                    api.getSubjects(type = 2, cat = 1, year = 2026, month = 1, limit = 200, offset = 0)
                }.exceptionOrNull()

            assertThat(error).isNotNull()
        }

    @Test
    fun `getSubjects uses the preferred base url when it is set`() =
        runTest {
            var host = ""
            val api = apiWithPreferred(preferred = "https://bgmapi.anibt.net", onRequest = { host = it.url.host })

            api.getSubjects(type = 2, cat = 1, year = 2026, month = 1, limit = 100, offset = 0)

            assertThat(host).isEqualTo("bgmapi.anibt.net")
        }

    @Test
    fun `getSubjects retries the same source then falls back to official`() =
        runTest {
            val visited = mutableListOf<String>()
            val api =
                apiWithHandler(preferred = "https://dead.example") { request ->
                    visited += request.url.host
                    if (request.url.host == "dead.example") {
                        respondJson("""{"message":"mirror down"}""", HttpStatusCode.NotFound)
                    } else {
                        respondJson(monthResponseJson, HttpStatusCode.OK)
                    }
                }

            val page = api.getSubjects(type = 2, cat = 1, year = 2026, month = 1, limit = 100, offset = 0)

            assertThat(page.total).isEqualTo(2)
            // 首选源 3 次（首次 + 2 次退避重试）后才换到官方
            assertThat(visited).containsExactly("dead.example", "dead.example", "dead.example", "api.bgm.tv").inOrder()
        }

    @Test
    fun `getSubjects keeps using the source that just worked`() =
        runTest {
            val visited = mutableListOf<String>()
            val api =
                apiWithHandler(preferred = "https://dead.example") { request ->
                    visited += request.url.host
                    if (request.url.host == "dead.example") {
                        respondJson("""{"message":"mirror down"}""", HttpStatusCode.NotFound)
                    } else {
                        respondJson(monthResponseJson, HttpStatusCode.OK)
                    }
                }

            api.getSubjects(type = 2, cat = 1, year = 2026, month = 1, limit = 100, offset = 0)
            visited.clear()
            api.getSubjects(type = 2, cat = 1, year = 2026, month = 1, limit = 100, offset = 100)

            // 第二次请求直接命中上次成功的源，不再在死源上重试三遍
            assertThat(visited).containsExactly("api.bgm.tv")
        }

    @Test
    fun `getSubjects does not retry on a non-retryable 4xx`() =
        runTest {
            var calls = 0
            val api =
                apiWithHandler(preferred = "") { _ ->
                    calls++
                    respondJson("""{"message":"limit too large"}""", HttpStatusCode.BadRequest)
                }

            runCatching {
                api.getSubjects(type = 2, cat = 1, year = 2026, month = 1, limit = 200, offset = 0)
            }

            // 400 属于「换源也没用」的错误，必须立刻上抛而不是把候选池跑一遍
            assertThat(calls).isEqualTo(1)
        }

    /** 用给定的首选地址构造 API，请求体固定返回 [monthResponseJson]。 */
    private fun apiWithPreferred(
        preferred: String,
        onRequest: (HttpRequestData) -> Unit = {},
    ): BangumiHttpApi =
        apiWithHandler(preferred, onRequest) { respondJson(monthResponseJson, HttpStatusCode.OK) }

    /** 用给定的首选地址 + 自定义响应逻辑构造 API。 */
    private fun apiWithHandler(
        preferred: String,
        onRequest: (HttpRequestData) -> Unit = {},
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): BangumiHttpApi {
        val engine =
            MockEngine { request ->
                onRequest(request)
                handler(request)
            }
        val client =
            HttpClient(engine) {
                expectSuccess = true
                install(ContentNegotiation) { json(BangumiHttpApi.JSON_CONFIG) }
            }
        return BangumiHttpApi(client, preferredBaseUrl = { preferred })
    }

    /** 以 JSON 内容 + 指定状态码作答（Ktor 的 `respond` 需要显式 Content-Type 才能被解码）。 */
    private fun MockRequestHandleScope.respondJson(
        content: String,
        status: HttpStatusCode,
    ): HttpResponseData =
        respond(
            content = ByteReadChannel(content),
            status = status,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
}
