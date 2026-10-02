package dev.frost819.newbv.bangumiapi.repository

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.bangumiapi.entity.BangumiCategory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * [BangumiRepository] 的真实网络集成测试。
 *
 * 直连 `https://api.bgm.tv`，**不需要任何凭证**（该端点裸请求即可）。
 * 单独的 `integrationTest` source set，不参与 `./gradlew test`，CI 不跑。
 *
 * 断言刻意保持宽松：只验证「通路可用 + 字段非空 + tag 覆盖率在预期量级」，
 * 不锁死具体条数（Bangumi 数据随时增补）。
 */
class BangumiRepositoryIntegrationTest {
    private val repository = BangumiRepository()

    @Test
    fun `v0 list returns 2026-01 tv anime with covers and ids`() =
        runTest {
            val items = repository.browseYearMonth(BangumiCategory.TvAnime, year = 2026, month = 1)

            assertThat(items).isNotEmpty()
            assertThat(items.all { it.id > 0 }).isTrue()
            assertThat(items.count { it.cover.isNotBlank() }).isEqualTo(items.size)
            assertThat(items.count { it.title.isNotBlank() }).isEqualTo(items.size)
            // 有评分部分必须单调不增（无评分条目 0.0 沉底，不参与比较）
            val scored = items.map { it.score }.filter { it > 0.0 }
            assertThat(scored.zipWithNext().all { (a, b) -> a >= b }).isTrue()
        }

    @Test
    fun `tag picker covers the majority of real items`() =
        runTest {
            // 实测口径：Tier1+Tier2、Tier3 关闭时，2026 全年四类可显示率 86.8%
            val items = repository.browseYearMonth(BangumiCategory.TvAnime, year = 2026, month = 1)

            val withTags = items.count { it.tags.isNotEmpty() }
            val ratio = withTags.toDouble() / items.size

            assertThat(ratio).isAtLeast(0.6)
        }

    @Test
    fun `ratings are either zero or within valid range`() =
        runTest {
            val items = repository.browseYearMonth(BangumiCategory.TvAnime, year = 2026, month = 1)

            assertThat(items.all { it.score == 0.0 || it.score in 1.0..10.0 }).isTrue()
        }

    @Test
    fun `anime movie merges the three non-tv cats`() =
        runTest {
            val items = repository.browseYearMonth(BangumiCategory.AnimeMovie, year = 2026, month = 7)

            assertThat(items).isNotEmpty()
            // 合并后仍须按 id 唯一
            assertThat(items.map { it.id }.toSet()).hasSize(items.size)
        }

    @Test
    fun `korean drama filter keeps only korean tagged items`() =
        runTest {
            val items = repository.browseYearMonth(BangumiCategory.KoreanDrama, year = 2026, month = 7)

            assertThat(items.all { "韩国" in it.metaTags }).isTrue()
        }

    @Test
    fun `year loading streams every month at least once`() =
        runTest {
            val results = mutableListOf<Int>()
            repository.loadYear(BangumiCategory.Movie, year = 2025, months = listOf(1, 2)).collect {
                results += it.month
            }

            assertThat(results).containsExactly(1, 2)
        }
}
