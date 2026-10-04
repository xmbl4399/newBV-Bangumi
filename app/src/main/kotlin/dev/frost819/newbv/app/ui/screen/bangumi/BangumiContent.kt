package dev.frost819.newbv.app.ui.screen.bangumi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.frost819.newbv.app.ui.component.FocusSaver
import dev.frost819.newbv.app.ui.component.GridSpacing
import dev.frost819.newbv.app.ui.component.ListFooterTip
import dev.frost819.newbv.app.ui.component.TopNav
import dev.frost819.newbv.app.ui.component.TopNavItem
import dev.frost819.newbv.app.ui.component.TvLazyVerticalGrid
import dev.frost819.newbv.app.ui.component.focusSaverItem
import dev.frost819.newbv.app.ui.component.rememberBangumiGridColumns
import dev.frost819.newbv.app.ui.component.videocard.BangumiCard
import dev.frost819.newbv.app.ui.component.videocard.BangumiCardData
import dev.frost819.newbv.app.ui.component.videocard.BangumiSkeletonCard
import dev.frost819.newbv.app.ui.component.videocard.MonthDividerSkeleton
import dev.frost819.newbv.app.ui.navigation.SearchResultRoute
import dev.frost819.newbv.app.viewmodel.bangumi.BangumiUiState
import dev.frost819.newbv.app.viewmodel.bangumi.BangumiViewModel
import dev.frost819.newbv.bangumiapi.entity.BangumiCategory

/**
 * 列表里月份的渲染顺序：12 → 1。
 *
 * **与请求顺序解耦**：请求按「最接近今天的月份优先」发（见 `BangumiViewModel.loadOrderMonths`），
 * 渲染永远按月份倒序，还没回来的月份用骨架占位。
 */
private val MONTHS_DESCENDING: List<Int> = (12 downTo 1).toList()

/**
 * Bangumi 年份 Tab 项。
 *
 * 年份共 21 项（2006 ~ 今年），用裸年份数字以压缩宽度，
 * 配合 [TopNav] 的紧凑模式才排得下。
 *
 * @property year 对应年份。
 */
private data class BangumiYearTab(
    val year: Int,
) : TopNavItem {
    override val displayName: String = year.toString()
}

/**
 * Bangumi 分类浏览内容。
 *
 * 由首页顶部的分类 Tab 驱动（[category]），内部再叠一层年份 [TopNav]，
 * 内容区为多列封面网格（列数见「界面设置 → 番剧网格列数」，默认 7 列），
 * **每月之前有一条「9月-4部」左对齐分割标题**。
 *
 * **不自带 Scaffold**：外层 `HomeContent` 已提供 Scaffold 与顶部分类 Tab，
 * 这里只负责「年份栏 + 网格」，避免出现两层 topBar。
 *
 * 每个分类通过 [hiltViewModel] 的 `key` 持有**独立 ViewModel 实例**，
 * 因此切走再切回时数据仍在，不需要重新拉 12 个月。
 *
 * @param category 当前分类。
 * @param navController 导航控制器（点击封面跳搜索结果页）。
 * @param focusSaver 焦点恢复器（由 MainScreen 共享传入）。
 * @param viewModel Bangumi ViewModel（按分类隔离实例）。
 */
@Composable
fun BangumiContent(
    category: BangumiCategory,
    navController: NavController,
    focusSaver: FocusSaver,
    viewModel: BangumiViewModel = hiltViewModel(key = category.name),
) {
    val uiState by viewModel.uiState.collectAsState()
    val years = remember { BangumiViewModel.selectableYears() }
    var focusOnContent by remember { mutableStateOf(false) }
    val yearNavFocusRequester = remember { FocusRequester() }

    // 分类由外层 Tab 决定：组合时同步给 ViewModel。
    // switchCategory 内部判重，重复组合不会重复发起请求。
    LaunchedEffect(category) {
        viewModel.switchCategory(category)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopNav(
            modifier = Modifier.focusRequester(yearNavFocusRequester),
            items = years.map { BangumiYearTab(it) },
            selectedIndex = years.indexOf(uiState.year).coerceAtLeast(0),
            isLargePadding = !focusOnContent,
            isCompact = true,
            onSelectedChanged = { item ->
                (item as? BangumiYearTab)?.let { viewModel.switchYear(it.year) }
            },
            onClick = { item ->
                (item as? BangumiYearTab)?.let { viewModel.switchYear(it.year) }
            },
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onFocusChanged { focusOnContent = it.hasFocus }
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Menu && event.type == KeyEventType.KeyUp) {
                            viewModel.refresh()
                            yearNavFocusRequester.requestFocus()
                            return@onPreviewKeyEvent true
                        }
                        false
                    },
        ) {
            BangumiGrid(
                uiState = uiState,
                focusSaver = focusSaver,
                onItemClick = { title ->
                    navController.navigate(SearchResultRoute(keyword = title))
                },
            )
        }
    }
}

/**
 * Bangumi 卡片网格。
 *
 * 数据随月份逐个到达，因此 [ListFooterTip] 用「是否仍在加载」当作 hasMore ——
 * 全年 12 个月加载完毕即列表终结。
 *
 * @param uiState 当前 UI 状态。
 * @param focusSaver 焦点恢复器。
 * @param onItemClick 点击封面回调（参数为条目标题，用于跳搜索结果页）。
 */
@Composable
private fun BangumiGrid(
    uiState: BangumiUiState,
    focusSaver: FocusSaver,
    onItemClick: (String) -> Unit,
) {
    val gridState = rememberLazyGridState()

    // 列数跟随「界面设置 → 番剧网格列数」（默认 7），改动后已打开的页面也会立即重排。
    val gridColumns = rememberBangumiGridColumns()

    // 切换年份或分类后回到顶部。
    // 关键：从搜索结果页返回时本页 composition 重建、本 effect 会重跑，若无条件
    // scrollToItem(0)，会把 rememberSaveable 恢复的滚动位置冲掉 —— 表现为返回后
    // 被弹回顶部；同时 FocusSaver 要恢复的那张卡被滚出可视区、尚未组合，
    // 焦点请求落空 ⇒ 焦点整体丢失。因此只在 (年份, 分类) **真的变化**时滚顶。
    var lastListKey by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(uiState.year, uiState.category) {
        val listKey = "${uiState.year}_${uiState.category.name}"
        if (lastListKey.isNotEmpty() && lastListKey != listKey) {
            gridState.scrollToItem(0)
        }
        lastListKey = listKey
    }

    // 固定按 12 → 1 遍历，**没回来的月份铺骨架而不是跳过**。
    // 起因：10 月（48 部、响应体最大）往往比 9 月（4 部）晚回来，而它排在月份倒序表的前面；
    // 若只渲染已到达的月份，首屏就会先出现「9月-4部」并把整列内容顶在下面，
    // 用户想看 10 月还得往上翻。铺骨架后 10 月的位置一进场就存在，数据到了是原地填充。
    val groupsByMonth = uiState.monthGroups.associateBy { it.month }

    TvLazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(gridColumns),
        // 左右跟随网格留白口径；顶部只留 12dp：年份栏自身已无下留白，
        // 再叠一份会在两者之间拉出一条明显空档。
        contentPadding =
            PaddingValues(
                start = GridSpacing.contentPadding,
                top = 12.dp,
                end = GridSpacing.contentPadding,
                bottom = 24.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MONTHS_DESCENDING.forEach { month ->
            val group = groupsByMonth[month]
            when {
                // 已到达：真实分组
                group != null -> {
                    item(
                        key = "month-$month",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        MonthDivider(title = group.title)
                    }
                    items(
                        items = group.items,
                        key = { it.id },
                    ) { item ->
                        BangumiCard(
                            data =
                                BangumiCardData.from(
                                    item = item,
                                    showTags = uiState.showTags,
                                    showEpisodes = uiState.showEpisodes,
                                ),
                            onClick = { onItemClick(item.title) },
                            modifier = Modifier.focusSaverItem(focusSaver, "${uiState.category.name}_${item.id}"),
                        )
                    }
                }

                // 还没回来：铺一行骨架守住位置（加载结束后仍未回来的月份不再占位）
                uiState.loading && month !in uiState.settledMonths -> {
                    item(
                        key = "month-$month-skeleton",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        MonthDividerSkeleton()
                    }
                    items(
                        count = gridColumns,
                        key = { "month-$month-skeleton-card-$it" },
                    ) {
                        BangumiSkeletonCard()
                    }
                }

                // 回来了但一部都没有（空月，或被跨月去重吃光）：不占位
                else -> Unit
            }
        }

        if (uiState.failedMonths > 0) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    modifier = Modifier.fillMaxWidth(),
                    text = "${uiState.failedMonths} 个月加载失败，按菜单键可重试",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            ListFooterTip(
                // 加载态已由骨架表达，这里只负责「失败 / 没有更多」两种收尾提示
                isLoading = false,
                isError = uiState.error,
                hasMore = uiState.loading,
                itemsIsEmpty = uiState.items.isEmpty(),
            )
        }
    }
}

/**
 * 月份分割标题（照搬 blbl-Bangumi 的「9月-4部」样式）。
 *
 * 占满一整行并左对齐，用次级文字色弱化，避免与封面标题抢注意力。
 *
 * 行高被压到 18sp（默认 titleMedium 是 24sp），且不再额外加顶部内边距 ——
 * 网格自身已有 12dp 的行间距，再叠 8dp 会让标题上下显得空。
 *
 * @param title 标题文本，形如「9月-4部」。
 */
@Composable
private fun MonthDivider(title: String) {
    Text(
        modifier = Modifier.fillMaxWidth(),
        text = title,
        textAlign = TextAlign.Start,
        style = MaterialTheme.typography.titleMedium.copy(lineHeight = 18.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
