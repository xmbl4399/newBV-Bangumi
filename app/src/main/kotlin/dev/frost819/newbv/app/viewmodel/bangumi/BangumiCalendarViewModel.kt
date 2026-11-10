package dev.frost819.newbv.app.viewmodel.bangumi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.frost819.newbv.bangumiapi.entity.BangumiCalendarGroup
import dev.frost819.newbv.bangumiapi.entity.BangumiWeekday
import dev.frost819.newbv.bangumiapi.repository.BangumiRepository
import dev.frost819.newbv.core.log.Loggers
import dev.frost819.newbv.data.datastore.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import javax.inject.Inject

/**
 * 新番表 UI 状态。
 *
 * @property groups 7 个星期分组，**已按「今日 → 往前回溯」排好**，首元素恒为今天。
 * @property loading 是否正在加载。
 * @property error 是否加载失败。
 * @property today 本次数据对应的「今天」，用于分组标题里的「今天」标记。
 */
data class BangumiCalendarUiState(
    val groups: List<BangumiCalendarGroup> = emptyList(),
    val loading: Boolean = false,
    val error: Boolean = false,
    val today: BangumiWeekday = BangumiWeekday.Monday,
) {
    /** 是否有可展示的数据。 */
    val hasItems: Boolean
        get() = groups.any { it.items.isNotEmpty() }
}

/**
 * 新番表（Bangumi 周历）ViewModel。
 *
 * 数据源是 `GET /calendar`（见 [BangumiRepository.loadCalendar]）——
 * **不是** B 站数据，也不按年月分页，一次请求拿到全年在播番剧。
 *
 * 排序语义：今天周几就从周几开始往前回溯（今天周三 → 周三、周二、周一、周日…），
 * 见 [BangumiWeekday.orderFrom]。
 *
 * @param bangumiRepository Bangumi 数据仓库。
 */
@HiltViewModel
class BangumiCalendarViewModel
    @Inject
    constructor(
        private val bangumiRepository: BangumiRepository,
    ) : ViewModel() {
        private val logger = Loggers.get("BangumiCalendarViewModel")

        private val _uiState = MutableStateFlow(BangumiCalendarUiState(today = todayWeekday()))
        val uiState: StateFlow<BangumiCalendarUiState> = _uiState.asStateFlow()

        /** 本次数据加载时的「隐藏无评分」设置，用于判断设置变更后是否需要重载。 */
        private var loadedHideNoScore: Boolean = Prefs.hideNoScoreMedia

        companion object {
            /** 单次加载超时（ms）。日历响应约 83 KB，实测 2~6 秒。 */
            private const val LOAD_TIMEOUT_MS = 15_000L

            /**
             * 取今天对应的星期。
             *
             * 用 [LocalDate] 而不是手工算 Calendar：`DayOfWeek.value` 与
             * [BangumiWeekday.id] 都是 1=周一 … 7=周日，无需映射表。
             */
            fun todayWeekday(): BangumiWeekday = BangumiWeekday.fromId(LocalDate.now().dayOfWeek.value)
        }

        init {
            load()
        }

        /**
         * 加载周历。
         *
         * 已加载且设置未变时不重复请求（本页在 Tab 间切换会反复组合）。
         *
         * @param force 是否强制重新拉取（下拉刷新/菜单键触发）。
         */
        fun load(force: Boolean = false) {
            val state = _uiState.value
            if (!force && state.groups.isNotEmpty() && !state.loading) {
                if (Prefs.hideNoScoreMedia == loadedHideNoScore) return
            }

            val today = todayWeekday()
            val hideNoScore = Prefs.hideNoScoreMedia
            loadedHideNoScore = hideNoScore

            viewModelScope.launch {
                _uiState.update { it.copy(loading = true, error = false, today = today) }
                runCatching {
                    withTimeout(LOAD_TIMEOUT_MS) {
                        bangumiRepository.loadCalendar(today = today, hideNoScore = hideNoScore)
                    }
                }.onSuccess { groups ->
                    _uiState.update {
                        it.copy(groups = groups, loading = false, error = false)
                    }
                }.onFailure { error ->
                    // 协程取消（如 ViewModel cleared）必须原样抛出，否则会破坏取消语义
                    if (error is CancellationException) throw error
                    logger.error(error) { "Failed to load bangumi calendar" }
                    _uiState.update { it.copy(loading = false, error = true) }
                }
            }
        }

        /** 刷新（忽略已加载缓存）。 */
        fun refresh() = load(force = true)
    }
