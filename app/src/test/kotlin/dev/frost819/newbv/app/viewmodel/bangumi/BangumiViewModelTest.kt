package dev.frost819.newbv.app.viewmodel.bangumi

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.bangumiapi.entity.BangumiCategory
import dev.frost819.newbv.bangumiapi.entity.BangumiItem
import dev.frost819.newbv.bangumiapi.entity.BangumiMonthResult
import dev.frost819.newbv.bangumiapi.repository.BangumiRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [BangumiViewModel] 的单元测试。
 *
 * 验证首屏加载、年份/分类切换（含重复切换不重复请求）、刷新、
 * 单月失败隔离、跨月去重与「话数/tag 显示开关」。
 * 用 MockK 替换 [BangumiRepository]，不发真实请求。
 */
class BangumiViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var repository: BangumiRepository

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun item(
        id: Int,
        date: String,
        score: Double = 8.0,
    ) = BangumiItem(
        id = id,
        title = "番剧 $id",
        date = date,
        eps = 12,
        score = score,
        votes = 100,
        cover = "https://example.com/$id.jpg",
        tags = listOf("奇幻", "原创"),
    )

    private fun success(
        month: Int,
        items: List<BangumiItem>,
    ) = BangumiMonthResult(year = 2026, month = month, items = items)

    private fun failure(month: Int) =
        BangumiMonthResult(year = 2026, month = month, items = emptyList(), error = IOException("down"))

    private fun stubYear(vararg results: BangumiMonthResult) {
        every {
            repository.loadYear(any(), any(), any(), any(), any())
        } returns flowOf(*results)
    }

    @Test
    fun `init loads current year and collects all months`() =
        runTest {
            // Given：两个月的月份乱序推送（11 月先到、12 月后到）
            stubYear(
                success(11, listOf(item(3, "2026-11-05"))),
                success(12, listOf(item(1, "2026-12-10"), item(2, "2026-12-20"))),
            )

            // When
            val viewModel = BangumiViewModel(repository)
            advanceUntilIdle()

            // Then：列表以月份倒序为主排序键，与到达顺序无关
            val state = viewModel.uiState.value
            assertThat(state.year).isEqualTo(BangumiViewModel.currentYear())
            assertThat(state.items.map { it.id }).containsExactly(1, 2, 3).inOrder()
            assertThat(state.loadedMonths).isEqualTo(2)
            assertThat(state.failedMonths).isEqualTo(0)
            assertThat(state.loading).isFalse()
            assertThat(state.error).isFalse()
        }

    @Test
    fun `init requests the tv anime category with the current year`() =
        runTest {
            stubYear(success(1, emptyList()))

            BangumiViewModel(repository)
            advanceUntilIdle()

            verify(exactly = 1) {
                repository.loadYear(
                    category = BangumiCategory.TvAnime,
                    year = BangumiViewModel.currentYear(),
                    months = any(),
                    concurrency = any(),
                    hideNoScore = any(),
                )
            }
        }

    @Test
    fun `switchYear triggers a new load for the target year`() =
        runTest {
            stubYear(success(1, emptyList()))
            val viewModel = BangumiViewModel(repository)
            advanceUntilIdle()

            // When
            val target = BangumiViewModel.currentYear() - 1
            viewModel.switchYear(target)
            advanceUntilIdle()

            // Then
            assertThat(viewModel.uiState.value.year).isEqualTo(target)
            verify(exactly = 1) {
                repository.loadYear(
                    category = any(),
                    year = target,
                    months = any(),
                    concurrency = any(),
                    hideNoScore = any(),
                )
            }
        }

    @Test
    fun `switchYear to the same year with data does not reload`() =
        runTest {
            stubYear(success(1, listOf(item(1, "2026-01-01"))))
            val viewModel = BangumiViewModel(repository)
            advanceUntilIdle()

            // When：切回当前年（已有数据）
            viewModel.switchYear(BangumiViewModel.currentYear())
            advanceUntilIdle()

            // Then：只发生过 init 的那一次请求
            verify(exactly = 1) { repository.loadYear(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `switchCategory reloads with the new category`() =
        runTest {
            stubYear(success(1, listOf(item(1, "2026-01-01"))))
            val viewModel = BangumiViewModel(repository)
            advanceUntilIdle()

            // When
            viewModel.switchCategory(BangumiCategory.JpDrama)
            advanceUntilIdle()

            // Then
            assertThat(viewModel.uiState.value.category).isEqualTo(BangumiCategory.JpDrama)
            verify(exactly = 1) {
                repository.loadYear(
                    category = BangumiCategory.JpDrama,
                    year = any(),
                    months = any(),
                    concurrency = any(),
                    hideNoScore = any(),
                )
            }
        }

    @Test
    fun `switchCategory to the same category with data does not reload`() =
        runTest {
            stubYear(success(1, listOf(item(1, "2026-01-01"))))
            val viewModel = BangumiViewModel(repository)
            advanceUntilIdle()

            // When：HomeContent 每次组合都会同步一次分类，不能因此重复拉取
            viewModel.switchCategory(BangumiCategory.TvAnime)
            advanceUntilIdle()

            // Then
            verify(exactly = 1) { repository.loadYear(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `refresh reloads the current year`() =
        runTest {
            stubYear(success(1, listOf(item(1, "2026-01-01"))))
            val viewModel = BangumiViewModel(repository)
            advanceUntilIdle()

            // When
            viewModel.refresh()
            advanceUntilIdle()

            // Then
            verify(exactly = 2) { repository.loadYear(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a failing month is isolated and keeps loaded items`() =
        runTest {
            stubYear(
                success(12, listOf(item(1, "2026-12-01"))),
                failure(11),
                success(10, listOf(item(2, "2026-10-01"))),
            )

            val viewModel = BangumiViewModel(repository)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.items.map { it.id }).containsExactly(1, 2).inOrder()
            assertThat(state.loadedMonths).isEqualTo(3)
            assertThat(state.failedMonths).isEqualTo(1)
            assertThat(state.error).isFalse()
        }

    @Test
    fun `error is reported when nothing loaded and at least one month failed`() =
        runTest {
            stubYear(failure(12))

            val viewModel = BangumiViewModel(repository)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.items).isEmpty()
            assertThat(state.error).isTrue()
            assertThat(state.loading).isFalse()
        }

    @Test
    fun `items are deduplicated by id across months`() =
        runTest {
            // 同一部番跨月放送时两个月都会返回它
            stubYear(
                success(2, listOf(item(7, "2026-02-01"))),
                success(1, listOf(item(7, "2026-02-01"))),
            )

            val viewModel = BangumiViewModel(repository)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.items).hasSize(1)
        }

    @Test
    fun `progressText reflects loaded months`() =
        runTest {
            stubYear(success(12, emptyList()), failure(11))

            val viewModel = BangumiViewModel(repository)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.progressText).isEqualTo("已加载 2/12 月")
        }

    @Test
    fun `selectableYears spans the current year back to 2006`() {
        val years = BangumiViewModel.selectableYears()

        assertThat(years.first()).isEqualTo(BangumiViewModel.currentYear())
        assertThat(years.last()).isEqualTo(BangumiViewModel.EARLIEST_YEAR)
        assertThat(years).hasSize(BangumiViewModel.currentYear() - BangumiViewModel.EARLIEST_YEAR + 1)
        assertThat(years).isInOrder(compareByDescending<Int> { it })
    }

    @Test
    fun `month groups carry a per-month count and keep month order`() =
        runTest {
            stubYear(
                success(11, listOf(item(3, "2026-11-05"))),
                success(12, listOf(item(1, "2026-12-10"), item(2, "2026-12-20"))),
            )

            val viewModel = BangumiViewModel(repository)
            advanceUntilIdle()

            val groups = viewModel.uiState.value.monthGroups
            assertThat(groups.map { it.month }).containsExactly(12, 11).inOrder()
            assertThat(groups.map { it.title }).containsExactly("12月-2部", "11月-1部").inOrder()
        }

    @Test
    fun `an empty month produces no divider`() =
        runTest {
            stubYear(success(12, listOf(item(1, "2026-12-10"))), success(11, emptyList()))

            val viewModel = BangumiViewModel(repository)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.monthGroups.map { it.month }).containsExactly(12)
        }

    @Test
    fun `showEpisodes is only true for tv anime`() {
        assertThat(BangumiUiState(category = BangumiCategory.TvAnime).showEpisodes).isTrue()
        assertThat(BangumiUiState(category = BangumiCategory.AnimeMovie).showEpisodes).isFalse()
        assertThat(BangumiUiState(category = BangumiCategory.JpDrama).showEpisodes).isFalse()
        assertThat(BangumiUiState(category = BangumiCategory.Movie).showEpisodes).isFalse()
    }

    @Test
    fun `showTags is only true for anime categories`() {
        assertThat(BangumiUiState(category = BangumiCategory.TvAnime).showTags).isTrue()
        assertThat(BangumiUiState(category = BangumiCategory.AnimeMovie).showTags).isTrue()
        assertThat(BangumiUiState(category = BangumiCategory.JpDrama).showTags).isFalse()
        assertThat(BangumiUiState(category = BangumiCategory.KoreanDrama).showTags).isFalse()
    }
}
