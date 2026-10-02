package dev.frost819.newbv.bangumiapi.repository

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.bangumiapi.cache.BangumiDiskCache
import dev.frost819.newbv.bangumiapi.entity.BangumiCategory
import dev.frost819.newbv.bangumiapi.entity.BangumiImages
import dev.frost819.newbv.bangumiapi.entity.BangumiRating
import dev.frost819.newbv.bangumiapi.entity.BangumiSubject
import dev.frost819.newbv.bangumiapi.entity.BangumiSubjectPage
import dev.frost819.newbv.bangumiapi.entity.BangumiTagVote
import dev.frost819.newbv.bangumiapi.http.BangumiHttpApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/**
 * [BangumiRepository] 的单元测试。
 *
 * 验证翻页终止条件、同月去重、评分排序、单月失败隔离、韩剧地区过滤、
 * 「其他动画」三分类合并、全年流式推送与磁盘缓存的命中/过滤分层。
 * 用 MockK 替换 [BangumiHttpApi]，不触网。
 */
class BangumiRepositoryTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var httpApi: BangumiHttpApi
    private lateinit var repository: BangumiRepository

    @BeforeEach
    fun setUp() {
        httpApi = mockk()
        repository = BangumiRepository(httpApi)
    }

    /** 带磁盘缓存的仓库（缓存目录用测试临时目录）。 */
    private fun cachedRepository(): BangumiRepository =
        BangumiRepository(httpApi, BangumiDiskCache(File(tempDir, BangumiDiskCache.DIR_NAME)))

    private fun subject(
        id: Int,
        date: String,
        score: Double = 8.0,
        metaTags: List<String> = emptyList(),
    ) = BangumiSubject(
        id = id,
        name = "name$id",
        nameCn = "中文$id",
        date = date,
        eps = 12,
        images = BangumiImages(common = "cover$id"),
        rating = BangumiRating(score = score, total = 100),
        tags = listOf(BangumiTagVote(name = "奇幻", count = 10)),
        metaTags = metaTags,
    )

    private fun page(
        total: Int,
        data: List<BangumiSubject>,
    ) = BangumiSubjectPage(total = total, limit = BangumiRepository.PAGE_LIMIT, offset = 0, data = data)

    private fun stubSinglePage(data: List<BangumiSubject>) {
        coEvery {
            httpApi.getSubjects(any(), any(), any(), any(), any(), any())
        } returns page(total = data.size, data = data)
    }

    @Test
    fun `browseYearMonth returns normalized items sorted by score desc`() =
        runTest {
            // Given：接口乱序返回，三条评分相同 —— 同分退化为按日期升序
            stubSinglePage(listOf(subject(1, "2026-01-05"), subject(2, "2026-01-20"), subject(3, "2026-01-11")))

            // When
            val items = repository.browseYearMonth(BangumiCategory.TvAnime, 2026, 1)

            // Then
            assertThat(items.map { it.id }).containsExactly(1, 3, 2).inOrder()
            assertThat(items.first().title).isEqualTo("中文1")
            assertThat(items.first().cover).isEqualTo("cover1")
        }

    @Test
    fun `browseYearMonth sorts by score first and sinks unrated items to the end`() =
        runTest {
            stubSinglePage(
                listOf(
                    subject(1, "2026-01-05", score = 6.0),
                    subject(2, "2026-01-20", score = 9.0),
                    subject(3, "2026-01-11", score = 0.0),
                ),
            )

            val items = repository.browseYearMonth(BangumiCategory.TvAnime, 2026, 1)

            // 9.0 -> 6.0 -> 无评分（0.0）
            assertThat(items.map { it.id }).containsExactly(2, 1, 3).inOrder()
        }

    @Test
    fun `browseYearMonth drops subjects with invalid id`() =
        runTest {
            stubSinglePage(listOf(subject(0, "2026-01-05"), subject(7, "2026-01-06")))

            val items = repository.browseYearMonth(BangumiCategory.TvAnime, 2026, 1)

            assertThat(items.map { it.id }).containsExactly(7)
        }

    @Test
    fun `browseYearMonth pages until a short page arrives`() =
        runTest {
            // Given：第一页满 100 条，第二页 2 条
            val fullPage = (1..BangumiRepository.PAGE_LIMIT).map { subject(it, "2026-01-01") }
            coEvery {
                httpApi.getSubjects(any(), any(), any(), any(), any(), 0)
            } returns page(total = 102, data = fullPage)
            coEvery {
                httpApi.getSubjects(any(), any(), any(), any(), any(), BangumiRepository.PAGE_LIMIT)
            } returns page(total = 102, data = listOf(subject(101, "2026-01-02"), subject(102, "2026-01-03")))

            // When
            val items = repository.browseYearMonth(BangumiCategory.TvAnime, 2026, 1)

            // Then
            assertThat(items).hasSize(102)
            coVerify(exactly = 1) {
                httpApi.getSubjects(any(), any(), any(), any(), any(), BangumiRepository.PAGE_LIMIT)
            }
        }

    @Test
    fun `browseYearMonth stops paging once a full page is shorter than limit`() =
        runTest {
            // Given：满页但 total 恰为 100 —— 下一页必然为空，当前实现靠"短页"终止
            val fullPage = (1..BangumiRepository.PAGE_LIMIT).map { subject(it, "2026-01-01") }
            coEvery {
                httpApi.getSubjects(any(), any(), any(), any(), any(), 0)
            } returns page(total = BangumiRepository.PAGE_LIMIT, data = fullPage)
            coEvery {
                httpApi.getSubjects(any(), any(), any(), any(), any(), BangumiRepository.PAGE_LIMIT)
            } returns page(total = BangumiRepository.PAGE_LIMIT, data = emptyList())

            val items = repository.browseYearMonth(BangumiCategory.TvAnime, 2026, 1)

            assertThat(items).hasSize(BangumiRepository.PAGE_LIMIT)
            coVerify(exactly = 1) {
                httpApi.getSubjects(any(), any(), any(), any(), any(), BangumiRepository.PAGE_LIMIT)
            }
        }

    @Test
    fun `browseYearMonth deduplicates subjects repeated across pages`() =
        runTest {
            val fullPage = (1..BangumiRepository.PAGE_LIMIT).map { subject(it, "2026-01-01") }
            coEvery {
                httpApi.getSubjects(any(), any(), any(), any(), any(), 0)
            } returns page(total = 101, data = fullPage)
            // 第二页重复返回 1 条已出现的 id
            coEvery {
                httpApi.getSubjects(any(), any(), any(), any(), any(), BangumiRepository.PAGE_LIMIT)
            } returns page(total = 101, data = listOf(subject(1, "2026-01-01")))

            val items = repository.browseYearMonth(BangumiCategory.TvAnime, 2026, 1)

            assertThat(items).hasSize(BangumiRepository.PAGE_LIMIT)
        }

    @Test
    fun `browseYearMonth passes category and month through to the api`() =
        runTest {
            stubSinglePage(emptyList())

            repository.browseYearMonth(BangumiCategory.Movie, 2025, 7)

            coVerify(exactly = 1) {
                httpApi.getSubjects(
                    type = BangumiCategory.Movie.type,
                    cat = BangumiCategory.Movie.cat,
                    year = 2025,
                    month = 7,
                    limit = BangumiRepository.PAGE_LIMIT,
                    offset = 0,
                )
            }
        }

    @Test
    fun `browseYearMonth merges web ova and movie cats for AnimeMovie`() =
        runTest {
            // WEB / OVA / 剧场版各返回一条，其中剧场版与 OVA 故意重复同 id
            coEvery { httpApi.getSubjects(2, 5, 2026, 7, any(), any()) } returns
                page(1, listOf(subject(1, "2026-07-01", score = 7.0)))
            coEvery { httpApi.getSubjects(2, 2, 2026, 7, any(), any()) } returns
                page(1, listOf(subject(2, "2026-07-02", score = 9.0)))
            coEvery { httpApi.getSubjects(2, 3, 2026, 7, any(), any()) } returns
                page(2, listOf(subject(2, "2026-07-02", score = 9.0), subject(3, "2026-07-03", score = 8.0)))

            val items = repository.browseYearMonth(BangumiCategory.AnimeMovie, 2026, 7)

            // 去重后 3 条，按评分降序 9.0 -> 8.0 -> 7.0
            assertThat(items.map { it.id }).containsExactly(2, 3, 1).inOrder()
        }

    @Test
    fun `browseYearMonth still returns data when one of the merged cats fails`() =
        runTest {
            // OVA 分类挂掉，WEB / 剧场版正常 —— 不应整月失败
            coEvery { httpApi.getSubjects(2, 5, 2026, 7, any(), any()) } returns
                page(1, listOf(subject(1, "2026-07-01", score = 7.0)))
            coEvery { httpApi.getSubjects(2, 2, 2026, 7, any(), any()) } throws IOException("ova down")
            coEvery { httpApi.getSubjects(2, 3, 2026, 7, any(), any()) } returns
                page(1, listOf(subject(3, "2026-07-03", score = 8.0)))

            val items = repository.browseYearMonth(BangumiCategory.AnimeMovie, 2026, 7)

            assertThat(items.map { it.id }).containsExactly(3, 1).inOrder()
        }

    @Test
    fun `browseYearMonth filters Korean dramas by meta tag`() =
        runTest {
            coEvery { httpApi.getSubjects(6, 6001, 2026, 7, any(), any()) } returns
                page(
                    2,
                    listOf(
                        subject(1, "2026-07-01", metaTags = listOf("韩国", "电视剧")),
                        subject(2, "2026-07-02", metaTags = listOf("日本", "电视剧")),
                    ),
                )

            val items = repository.browseYearMonth(BangumiCategory.KoreanDrama, 2026, 7)

            assertThat(items.map { it.id }).containsExactly(1)
        }

    @Test
    fun `browseYearMonth does not filter meta tags for non Korean categories`() =
        runTest {
            coEvery { httpApi.getSubjects(6, 1, 2026, 7, any(), any()) } returns
                page(1, listOf(subject(9, "2026-07-01", metaTags = listOf("日本"))))

            val items = repository.browseYearMonth(BangumiCategory.JpDrama, 2026, 7)

            assertThat(items.map { it.id }).containsExactly(9)
        }

    @Test
    fun `browseYearMonth propagates network failure`() =
        runTest {
            coEvery {
                httpApi.getSubjects(any(), any(), any(), any(), any(), any())
            } throws IOException("boom")

            val error =
                runCatching { repository.browseYearMonth(BangumiCategory.TvAnime, 2026, 1) }.exceptionOrNull()

            assertThat(error).isInstanceOf(IOException::class.java)
        }

    @Test
    fun `loadYear emits one result per requested month`() =
        runTest {
            stubSinglePage(listOf(subject(1, "2026-01-01")))

            repository.loadYear(BangumiCategory.TvAnime, 2026).test {
                val months = mutableListOf<Int>()
                repeat(12) {
                    val result = awaitItem()
                    months += result.month
                    assertThat(result.isSuccess).isTrue()
                }
                assertThat(months).containsExactlyElementsIn(1..12)
                awaitComplete()
            }
            coVerify(exactly = 12) { httpApi.getSubjects(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `loadYear isolates a single failing month`() =
        runTest {
            // 先声明通用桩，再声明失败月，MockK 取后声明者
            stubSinglePage(listOf(subject(1, "2026-01-01")))
            coEvery {
                httpApi.getSubjects(any(), any(), any(), 3, any(), any())
            } throws IOException("month 3 down")

            repository.loadYear(BangumiCategory.TvAnime, 2026).test {
                val results = (1..12).map { awaitItem() }
                val failed = results.filter { !it.isSuccess }

                assertThat(failed.map { it.month }).containsExactly(3)
                assertThat(results.filter { it.isSuccess }.map { it.month }).hasSize(11)
                assertThat(failed.single().items).isEmpty()
                assertThat(failed.single().error).isInstanceOf(IOException::class.java)
                awaitComplete()
            }
        }

    @Test
    fun `loadYear honours a custom month list`() =
        runTest {
            stubSinglePage(emptyList())

            repository.loadYear(BangumiCategory.JpDrama, 2026, months = listOf(4, 10)).test {
                assertThat(listOf(awaitItem().month, awaitItem().month)).containsExactly(4, 10)
                awaitComplete()
            }
            coVerify(exactly = 2) { httpApi.getSubjects(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `loadYear emits every month exactly once even at concurrency one`() =
        runTest {
            stubSinglePage(emptyList())

            repository.loadYear(BangumiCategory.TvAnime, 2026, concurrency = 1).test {
                // 推送顺序不保证（并发拉取，先完成先推），只断言集合完整。
                // 界面需要的稳定顺序由 ViewModel 按 month 排序保证。
                val months = (1..12).map { awaitItem().month }
                assertThat(months).containsExactlyElementsIn(1..12)
                awaitComplete()
            }
        }

    @Test
    fun `hideNoScore drops unrated items but keeps the rest ordered`() =
        runTest {
            stubSinglePage(
                listOf(
                    subject(1, "2026-01-05", score = 6.0),
                    subject(2, "2026-01-20", score = 9.0),
                    subject(3, "2026-01-11", score = 0.0),
                    subject(4, "2026-01-02", score = 7.5),
                ),
            )

            val items = repository.browseYearMonth(BangumiCategory.TvAnime, 2026, 1, hideNoScore = true)

            assertThat(items.map { it.id }).containsExactly(2, 4, 1).inOrder()
            assertThat(items.all { it.hasScore }).isTrue()
        }

    @Test
    fun `hideNoScore defaults to off when not requested`() =
        runTest {
            stubSinglePage(listOf(subject(1, "2026-01-05", score = 9.0), subject(2, "2026-01-06", score = 0.0)))

            val items = repository.browseYearMonth(BangumiCategory.TvAnime, 2026, 1)

            assertThat(items).hasSize(2)
        }

    @Test
    fun `loadYear forwards hideNoScore to every month`() =
        runTest {
            stubSinglePage(listOf(subject(1, "2026-01-05", score = 0.0)))

            repository.loadYear(BangumiCategory.TvAnime, 2026, months = listOf(3), hideNoScore = true).test {
                val result = awaitItem()
                assertThat(result.items).isEmpty()
                awaitComplete()
            }
        }

    @Test
    fun `second browse of the same month is served from the disk cache`() =
        runTest {
            val cached = cachedRepository()
            stubSinglePage(listOf(subject(1, "2026-01-05", score = 8.0)))

            val first = cached.browseYearMonth(BangumiCategory.TvAnime, 2026, 1)
            val second = cached.browseYearMonth(BangumiCategory.TvAnime, 2026, 1)

            assertThat(second).isEqualTo(first)
            coVerify(exactly = 1) { httpApi.getSubjects(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `cache keys separate months so each month is fetched once`() =
        runTest {
            val cached = cachedRepository()
            stubSinglePage(listOf(subject(1, "2026-01-05", score = 8.0)))

            cached.browseYearMonth(BangumiCategory.TvAnime, 2026, 1)
            cached.browseYearMonth(BangumiCategory.TvAnime, 2026, 2)

            coVerify(exactly = 2) { httpApi.getSubjects(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `hideNoScore is applied above the cache so both variants reuse one entry`() =
        runTest {
            val cached = cachedRepository()
            stubSinglePage(listOf(subject(1, "2026-01-05", score = 9.0), subject(2, "2026-01-06", score = 0.0)))

            val all = cached.browseYearMonth(BangumiCategory.TvAnime, 2026, 1)
            val rated = cached.browseYearMonth(BangumiCategory.TvAnime, 2026, 1, hideNoScore = true)

            assertThat(all).hasSize(2)
            assertThat(rated.map { it.id }).containsExactly(1)
            // 缓存存的是「未过滤」的整月结果，因此过滤变体不会触发第二次请求
            coVerify(exactly = 1) { httpApi.getSubjects(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `merged cats cache each sub category independently`() =
        runTest {
            val cached = cachedRepository()
            coEvery { httpApi.getSubjects(2, 5, 2026, 7, any(), any()) } returns
                page(1, listOf(subject(1, "2026-07-01", score = 7.0)))
            coEvery { httpApi.getSubjects(2, 2, 2026, 7, any(), any()) } returns
                page(1, listOf(subject(2, "2026-07-02", score = 9.0)))
            coEvery { httpApi.getSubjects(2, 3, 2026, 7, any(), any()) } returns
                page(1, listOf(subject(3, "2026-07-03", score = 8.0)))

            cached.browseYearMonth(BangumiCategory.AnimeMovie, 2026, 7)
            cached.browseYearMonth(BangumiCategory.AnimeMovie, 2026, 7)

            // 三个子分类各只请求一次，第二次整月走缓存
            coVerify(exactly = 3) { httpApi.getSubjects(any(), any(), any(), any(), any(), any()) }
        }
}
