package dev.frost819.newbv.app.viewmodel.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.frost819.newbv.app.ui.state.search.SearchResultItem
import dev.frost819.newbv.app.ui.state.search.SearchResultUiState
import dev.frost819.newbv.app.ui.state.search.TypedSearchResult
import dev.frost819.newbv.biliapi.entity.ApiType
import dev.frost819.newbv.biliapi.repositories.SearchFilterDuration
import dev.frost819.newbv.biliapi.repositories.SearchFilterOrderType
import dev.frost819.newbv.biliapi.repositories.SearchRepository
import dev.frost819.newbv.biliapi.repositories.SearchType
import dev.frost819.newbv.core.log.Loggers
import dev.frost819.newbv.data.datastore.Prefs
import dev.frost819.newbv.data.repository.SearchHistoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import dev.frost819.newbv.data.datastore.ApiType as DataApiType

/**
 * 搜索结果页 ViewModel。
 *
 * 管理 5 类搜索结果（视频/番剧/影视/用户/直播间）的加载、分页、筛选。
 *
 * @param searchRepository 搜索数据仓库
 * @param searchHistoryRepository 搜索历史仓库：本页是所有搜索入口的汇聚点，
 *   进入本页即视为一次搜索，历史统一在这里落库
 */
@HiltViewModel
class SearchResultViewModel
    @Inject
    constructor(
        private val searchRepository: SearchRepository,
        private val searchHistoryRepository: SearchHistoryRepository,
    ) : ViewModel() {
        private val logger = Loggers.get("SearchResultViewModel")

        companion object {
            private const val LOAD_TIMEOUT_MS = 10_000L
        }

        private val _uiState = MutableStateFlow(SearchResultUiState())
        val uiState = _uiState.asStateFlow()

        /**
         * 设置搜索关键词并启动搜索。
         *
         * 重置所有分页和结果，对 4 种类型并行加载第一页。
         *
         * 进入本页的入口不止搜索框：自制番剧页点卡片、详情页点 tag 都是直接导航到
         * 本页（见 `BangumiContent` / `VideoDetailScreen`），这些入口此前不会写搜索
         * 历史。因此把「一次搜索」的落库收敛到本方法，任何入口都自动被记录；
         * [SearchHistoryRepository.addHistory] 幂等（同词仅刷新时间）且无痕模式下
         * 自行跳过，搜索框入口重复写入无副作用。
         *
         * @param keyword 搜索关键词；空白串直接忽略（避免写入空历史）
         */
        fun search(keyword: String) {
            if (keyword.isBlank()) return
            _uiState.update {
                it.copy(
                    keyword = keyword,
                    results =
                        SearchType.entries.associateWith { type ->
                            TypedSearchResult(type = type)
                        },
                )
            }
            viewModelScope.launch {
                // 用户可能立刻返回，写入不能随本页销毁而被取消
                withContext(NonCancellable) { searchHistoryRepository.addHistory(keyword) }
            }
            SearchType.entries.forEach { loadMore(it) }
        }

        /**
         * 切换当前激活的搜索类型 Tab。
         */
        fun switchType(type: SearchType) {
            _uiState.update { it.copy(activeType = type) }
            val result = _uiState.value.results[type] ?: return
            if (result.items.isEmpty() && !result.isLoading && result.hasMore) {
                loadMore(type)
            }
        }

        /**
         * 加载更多数据。
         *
         * 若当前类型正在加载或已无更多数据，则跳过。
         */
        fun loadMore(type: SearchType) {
            val state = _uiState.value
            val keyword = state.keyword
            if (keyword.isBlank()) return

            val result = state.results[type] ?: return
            if (result.isLoading || !result.hasMore) return

            updateResult(type) { it.copy(isLoading = true, error = false) }

            viewModelScope.launch {
                runCatching {
                    withTimeout(LOAD_TIMEOUT_MS) {
                        searchRepository.searchType(
                            keyword = keyword,
                            type = type,
                            tid = null,
                            order = state.selectedOrder,
                            duration = state.selectedDuration,
                            page = result.page,
                            preferApiType = if (Prefs.apiType == DataApiType.App) ApiType.App else ApiType.Web,
                        )
                    }
                }.onSuccess { searchResult ->
                    val newItems =
                        when (type) {
                            SearchType.Video -> searchResult.videos.map { SearchResultItem.VideoItem(it) }
                            SearchType.MediaBangumi, SearchType.MediaFt ->
                                searchResult.pgcs.map {
                                    SearchResultItem.PgcItem(
                                        it,
                                    )
                                }
                            SearchType.BiliUser -> searchResult.users.map { SearchResultItem.UserItem(it) }
                            SearchType.LiveRoom -> searchResult.liveRooms.map { SearchResultItem.LiveRoomItem(it) }
                        }
                    updateResult(type) {
                        val existingIds =
                            it.items
                                .mapNotNull { item ->
                                    when (item) {
                                        is SearchResultItem.VideoItem -> "v_${item.video.aid}"
                                        is SearchResultItem.PgcItem -> "p_${item.pgc.seasonId}"
                                        is SearchResultItem.UserItem -> "u_${item.user.mid}"
                                        is SearchResultItem.LiveRoomItem -> "l_${item.room.roomId}"
                                    }
                                }.toSet()
                        val dedupedNewItems =
                            newItems.filter { item ->
                                val key =
                                    when (item) {
                                        is SearchResultItem.VideoItem -> "v_${item.video.aid}"
                                        is SearchResultItem.PgcItem -> "p_${item.pgc.seasonId}"
                                        is SearchResultItem.UserItem -> "u_${item.user.mid}"
                                        is SearchResultItem.LiveRoomItem -> "l_${item.room.roomId}"
                                    }
                                key !in existingIds
                            }
                        // hasMore 由 Repository 根据 API 返回的 numPages 判断
                        val hasMore = searchResult.hasMore && dedupedNewItems.isNotEmpty()
                        it.copy(
                            items = it.items + dedupedNewItems,
                            page = searchResult.page,
                            isLoading = false,
                            hasMore = hasMore,
                            error = false,
                        )
                    }
                    logger.info {
                        "Loaded search result: type=$type, new=${newItems.size}, total=${result.items.size + newItems.size}"
                    }
                }.onFailure { e ->
                    if (e is CancellationException && e !is TimeoutCancellationException) {
                        throw e
                    }
                    logger.warn { "Failed to load search result: type=$type, $e" }
                    updateResult(type) { it.copy(isLoading = false, error = true) }
                }
            }
        }

        /**
         * 更新筛选条件并重新搜索。
         */
        fun updateFilter(
            order: SearchFilterOrderType,
            duration: SearchFilterDuration,
        ) {
            _uiState.update {
                it.copy(selectedOrder = order, selectedDuration = duration, showFilter = false)
            }
            search(_uiState.value.keyword)
        }

        /** 显示/隐藏筛选弹窗。 */
        fun toggleFilter(show: Boolean) {
            _uiState.update { it.copy(showFilter = show) }
        }

        private fun updateResult(
            type: SearchType,
            block: (TypedSearchResult) -> TypedSearchResult,
        ) {
            _uiState.update { state ->
                val current = state.results[type] ?: return@update state
                state.copy(results = state.results.toMutableMap().apply { put(type, block(current)) })
            }
        }
    }
