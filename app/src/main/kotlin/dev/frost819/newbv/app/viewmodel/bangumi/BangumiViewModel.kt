package dev.frost819.newbv.app.viewmodel.bangumi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.frost819.newbv.bangumiapi.entity.BANGUMI_TYPE_ANIME
import dev.frost819.newbv.bangumiapi.entity.BangumiCategory
import dev.frost819.newbv.bangumiapi.entity.BangumiItem
import dev.frost819.newbv.bangumiapi.repository.BangumiRepository
import dev.frost819.newbv.core.log.Loggers
import dev.frost819.newbv.data.datastore.Prefs
import java.util.Calendar
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 一个月份分组。
 *
 * 列表里每月的封面之前插入一条左对齐分割标题（如「9月-4部」），
 * 让用户一眼看出某月有多少部，而不是把 12 个月的作品混成一整片。
 *
 * @property month 月份（1~12）。
 * @property items 该月条目，已按评分降序并跨月去重。
 */
data class BangumiMonthGroup(
    val month: Int,
    val items: List<BangumiItem>,
) {
    /** 分组标题，形如「9月-4部」。 */
    val title: String
        get() = "${month}月-${items.size}部"
}

/**
 * Bangumi 分类浏览页 UI 状态。
 *
 * @property category 当前浏览分类（由首页顶部的 Tab 决定）。
 * @property year 当前展示的年份。
 * @property monthGroups 按月分组后的**已到达**数据。**以月份为主排序键**：12 月 → 1 月，
 *   月内顺序由 [BangumiRepository.ITEM_ORDER]（评分降序）决定。空月份不占分组。
 * @property items 全部分组的展平结果（与 [monthGroups] 顺序一致），
 *   供「是否为空」「首屏失败」等判断使用。
 * @property settledMonths **已经收到结果**的月份（含空月与失败月）。
 *   UI 靠它区分两种"没有分组"：不在集合里 = 还没回来 → 铺骨架占位；
 *   在集合里但没分组 = 回来了但一部都没有 → 不占位。
 *   这样 12 个月的**占位顺序在加载全程保持稳定**，新数据只会原地填充，
 *   不会因为「9 月先回来」就把 9 月摆到列表顶部、逼用户往上翻去找 10 月。
 * @property loadedMonths 已完成的月份数（含失败月）。
 * @property totalMonths 需要加载的月份总数（固定 12）。
 * @property failedMonths 加载失败的月份数。
 * @property loading 是否仍在加载中。
 */
data class BangumiUiState(
    val category: BangumiCategory = BangumiCategory.TvAnime,
    val year: Int = 0,
    val monthGroups: List<BangumiMonthGroup> = emptyList(),
    val items: List<BangumiItem> = emptyList(),
    val settledMonths: Set<Int> = emptySet(),
    val loadedMonths: Int = 0,
    val totalMonths: Int = MONTHS_OF_YEAR,
    val failedMonths: Int = 0,
    val loading: Boolean = false,
) {
    /** 首屏彻底失败（还没有任何数据、且至少一个月失败）。 */
    val error: Boolean
        get() = !loading && items.isEmpty() && failedMonths > 0

    /** 加载进度文案，如「已加载 8/12 月」。 */
    val progressText: String
        get() = "已加载 $loadedMonths/$totalMonths 月"

    /** 是否显示分集信息。照搬 blbl-Bangumi：**仅 TV 动画显示**，剧场/剧集/电影不显示。 */
    val showEpisodes: Boolean
        get() = category == BangumiCategory.TvAnime

    /** 是否显示流派 tag。照搬 blbl-Bangumi：**仅动画向显示**，三次元（剧集/电影）不显示。 */
    val showTags: Boolean
        get() = category.type == BANGUMI_TYPE_ANIME
}

/** 一年 12 个月。 */
private const val MONTHS_OF_YEAR = 12

/**
 * Bangumi 分类浏览页 ViewModel。
 *
 * 数据流：`BangumiRepository.loadYear()` 并发拉取全年 12 个月，
 * **每完成一个月推一次结果**，因此首屏会在首个月份返回时立即出图，
 * 而不是等全年 12 个请求全部结束。
 *
 * 每个分类 Tab 由调用方通过 `hiltViewModel(key = category.name)` 持有**独立实例**，
 * 因此切换 Tab 不会互相污染状态；分类变化通过 [switchCategory] 传入。
 *
 * @param bangumiRepository Bangumi 数据仓库。
 */
@HiltViewModel
class BangumiViewModel
    @Inject
    constructor(
        private val bangumiRepository: BangumiRepository,
    ) : ViewModel() {
        private val logger = Loggers.get("BangumiViewModel")

        private val _uiState = MutableStateFlow(BangumiUiState(year = currentYear()))
        val uiState: StateFlow<BangumiUiState> = _uiState.asStateFlow()

        /** 当前加载任务；切换年份/分类时先取消，避免旧结果污染新状态。 */
        private var loadJob: Job? = null

        /**
         * 上一次加载实际采用的「隐藏无评分」设置。
         *
         * 用户在设置页改动该开关后，本 ViewModel 仍是同一个实例（Activity 作用域），
         * 靠比较这个快照决定「回到该 Tab 时是否需要重新拉取」。
         */
        private var loadedHideNoScore: Boolean = Prefs.hideNoScoreMedia

        /**
         * 加载代次。每次 [loadYear] 递增。
         *
         * `cancel()` 是异步的：被取消的旧协程仍会跑完 `finally`，
         * 若无条件写 `loading = false`，就会**覆盖掉新一代刚设好的 loading 状态**，
         * 表现为「页面在转圈但状态说加载完了」。所有状态写入都用代次过滤。
         */
        private var loadGeneration = 0

        init {
            loadYear(_uiState.value.year)
        }

        /**
         * 切换到指定分类。
         *
         * 目标分类与当前一致、已有数据、且「隐藏无评分」设置未变时不重复请求
         * （`HomeContent` 在每次组合时都会调用，不能无条件重拉）。
         *
         * @param category 目标分类。
         */
        fun switchCategory(category: BangumiCategory) {
            if (category == _uiState.value.category &&
                _uiState.value.items.isNotEmpty() &&
                !settingsChanged()
            ) {
                return
            }
            _uiState.update { it.copy(category = category) }
            loadYear(_uiState.value.year)
        }

        /**
         * 切换到指定年份。
         *
         * 目标年份与当前一致、已有数据、且「隐藏无评分」设置未变时不重复请求。
         *
         * @param year 目标年份（4 位）。
         */
        fun switchYear(year: Int) {
            if (year == _uiState.value.year && _uiState.value.items.isNotEmpty() && !settingsChanged()) {
                return
            }
            loadYear(year)
        }

        /**
         * 「隐藏无评分」设置是否与上次加载时不一致。
         *
         * @return true 表示需要重新拉取。
         */
        private fun settingsChanged(): Boolean = Prefs.hideNoScoreMedia != loadedHideNoScore

        /**
         * 重新加载当前分类的当前年份（菜单键触发）。
         */
        fun refresh() {
            loadYear(_uiState.value.year)
        }

        /**
         * 加载指定年份的全年数据（分类取当前值）。
         *
         * 单月失败会被计入 [BangumiUiState.failedMonths]，不打断其余月份。
         *
         * @param year 目标年份。
         */
        private fun loadYear(year: Int) {
            val category = _uiState.value.category
            val hideNoScore = Prefs.hideNoScoreMedia
            loadedHideNoScore = hideNoScore
            loadJob?.cancel()
            val generation = ++loadGeneration
            _uiState.value = BangumiUiState(category = category, year = year, loading = true)
            loadJob =
                viewModelScope.launch {
                    // 以月份为 key 收集：按月展平，保证列表顺序与到达顺序无关
                    val byMonth = LinkedHashMap<Int, List<BangumiItem>>()
                    var loaded = 0
                    var failed = 0

                    try {
                        bangumiRepository
                            .loadYear(
                                category = category,
                                year = year,
                                months = loadOrderMonths(year),
                                concurrency = LOAD_CONCURRENCY,
                                hideNoScore = hideNoScore,
                            )
                            .collect { result ->
                                if (generation != loadGeneration) return@collect
                                loaded++
                                if (result.isSuccess) {
                                    byMonth[result.month] = result.items
                                } else {
                                    failed++
                                    result.error?.let { cause ->
                                        logger.error(cause) { "Bangumi failed: $category $year-${result.month}" }
                                    }
                                }
                                // 月份为主排序键（12 → 1），并跨月按 id 去重：
                                // 同一部番跨月放送时两个月都会返回它，不去重会出现重复卡片
                                val seen = HashSet<Int>()
                                val groups =
                                    byMonth.entries
                                        .sortedByDescending { it.key }
                                        .map { (month, monthItems) ->
                                            BangumiMonthGroup(
                                                month = month,
                                                items = monthItems.filter { seen.add(it.id) },
                                            )
                                        }
                                        // 该月一部都没有就不出「9月-0部」这种空标题
                                        .filter { it.items.isNotEmpty() }
                                _uiState.update {
                                    it.copy(
                                        monthGroups = groups,
                                        items = groups.flatMap { group -> group.items },
                                        settledMonths = it.settledMonths + result.month,
                                        loadedMonths = loaded,
                                        failedMonths = failed,
                                    )
                                }
                            }
                    } catch (error: CancellationException) {
                        // 切换年份/分类导致的取消不是错误，直接让出
                        throw error
                    } finally {
                        if (generation == loadGeneration) {
                            _uiState.update { it.copy(loading = false) }
                        }
                    }
                }
        }

        /**
         * 月份请求顺序：让**最接近今天的月份最先开始下载**。
         *
         * 用户最关心的是"即将开播 / 刚开播"那几个月，而它们的响应体也最大
         * （整季 40~80 部，JSON 上百 KB），往往是全场最慢的请求。
         * 若按 12→1 入队，它要排在两个未来空月份之后才轮得到，首屏白等一轮并发窗口。
         *
         * 当前年份：`[下个月 … 12 月] + [当前月 … 1 月]`（2026-09 → 10,11,12,9,8,…,1）；
         * 其它年份：12 → 1（往年各月数据量均衡，无需特判）。
         *
         * 这只影响**请求先后**，不影响列表顺序 —— 列表永远按 12→1 渲染。
         *
         * 用 [Calendar] 而非 `java.time`：项目 minSdk 21 且未开启 desugaring。
         *
         * @param year 目标年份。
         * @return 月份列表（12 项，不重不漏）。
         */
        private fun loadOrderMonths(year: Int): List<Int> {
            val now = Calendar.getInstance()
            if (year != now.get(Calendar.YEAR)) return (MONTHS_OF_YEAR downTo 1).toList()
            val currentMonth = now.get(Calendar.MONTH) + 1
            return ((currentMonth + 1)..MONTHS_OF_YEAR).toList() + (currentMonth downTo 1).toList()
        }

        companion object {
            /** 年份 Tab 可选的最早年份（Bangumi 数据在更早年份稀疏且不完整）。 */
            const val EARLIEST_YEAR: Int = 2006

            /** 全年加载的并发上限：比仓库默认的 3 略高 —— 首屏等待对体感影响最大。 */
            private const val LOAD_CONCURRENCY: Int = 4

            /**
             * 当前年份。
             *
             * 用 [Calendar] 而非 `java.time`：项目 minSdk 21，未开启 core library desugaring 时
             * `java.time` 在 API < 26 会崩。
             */
            fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

            /**
             * 可选的年份列表（当前年倒序到 [EARLIEST_YEAR]）。
             *
             * @return 例如 2026 年时返回 `[2026, 2025, …, 2007, 2006]`（共 21 项）。
             */
            fun selectableYears(): List<Int> = (currentYear() downTo EARLIEST_YEAR).toList()
        }
    }
