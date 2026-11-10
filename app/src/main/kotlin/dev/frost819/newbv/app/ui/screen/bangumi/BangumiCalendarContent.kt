package dev.frost819.newbv.app.ui.screen.bangumi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import dev.frost819.newbv.app.ui.component.TvLazyVerticalGrid
import dev.frost819.newbv.app.ui.component.focusSaverItem
import dev.frost819.newbv.app.ui.component.rememberBangumiGridColumns
import dev.frost819.newbv.app.ui.component.videocard.BangumiCard
import dev.frost819.newbv.app.ui.component.videocard.BangumiCardData
import dev.frost819.newbv.app.ui.component.videocard.BangumiSkeletonCard
import dev.frost819.newbv.app.ui.component.videocard.MonthDividerSkeleton
import dev.frost819.newbv.app.ui.navigation.SearchResultRoute
import dev.frost819.newbv.app.viewmodel.bangumi.BangumiCalendarViewModel
import dev.frost819.newbv.bangumiapi.entity.BangumiCalendarGroup

/**
 * 新番表内容（Bangumi 周历）。
 *
 * 数据源为 `GET /calendar`，与同目录下的 [BangumiContent]（按年月的分类浏览）
 * 是两套互不相干的数据：
 * - [BangumiContent] 回答「某年某月有哪些番」，按评分排序、按月份分组、可翻 21 年；
 * - 本页回答「**这周每天播什么**」，按星期分组、按「今日 → 往前回溯」排序。
 *
 * 因此本页**没有年份 Tab**，只有一层网格 + 7 个星期分割标题。
 *
 * 排序语义见 [dev.frost819.newbv.bangumiapi.entity.BangumiWeekday.orderFrom]：
 * 今天周三 → 周三、周二、周一、周日、周六、周五、周四。
 *
 * **不自带 Scaffold**：外层 `HomeContent` 已提供顶部分类 Tab。
 *
 * @param navController 导航控制器（点击封面跳搜索结果页）。
 * @param focusSaver 焦点恢复器（由 MainScreen 共享传入）。
 * @param viewModel 新番表 ViewModel（单实例，不按分类隔离）。
 */
@Composable
fun BangumiCalendarContent(
    navController: NavController,
    focusSaver: FocusSaver,
    viewModel: BangumiCalendarViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()

    // 列数跟随「界面设置 → 番剧网格列数」（默认 6），与分类浏览页同一口径
    val gridColumns = rememberBangumiGridColumns()

    TvLazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(gridColumns),
        // 顶部只留 12dp：本页没有年份栏，再多留会与首页 Tab 之间出现空档
        contentPadding =
            PaddingValues(
                start = GridSpacing.contentPadding,
                top = 12.dp,
                end = GridSpacing.contentPadding,
                bottom = 24.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier.onPreviewKeyEvent { event ->
                // 菜单键 = 重新拉取周历（接口给了 1 小时缓存，这里按需强制刷新）
                if (event.key == Key.Menu && event.type == KeyEventType.KeyUp) {
                    viewModel.refresh()
                    true
                } else {
                    false
                }
            },
    ) {
        // 首次加载且没有任何数据：铺骨架，避免闪一下空白再出内容
        if (uiState.loading && !uiState.hasItems) {
            item(
                key = "calendar-loading-header",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                MonthDividerSkeleton()
            }
            items(count = gridColumns * 2, key = { "calendar-skeleton-$it" }) {
                BangumiSkeletonCard()
            }
        }

        uiState.groups.forEach { group ->
            // 空分组也保留标题：「今天没有可显示的番剧」是有效信息，
            // 直接吞掉会让用户以为那天的数据没加载出来
            item(
                key = "weekday-${group.weekday.id}",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                WeekdayDivider(group = group)
            }
            items(
                items = group.items,
                key = { "${group.weekday.id}_${it.id}" },
            ) { item ->
                BangumiCard(
                    data =
                        BangumiCardData.from(
                            item = item,
                            // 日历不返回集数，固定关掉集数角标
                            showTags = true,
                            showEpisodes = false,
                        ),
                    onClick = { navController.navigate(SearchResultRoute(keyword = item.title)) },
                    modifier = Modifier.focusSaverItem(focusSaver, "calendar_${item.id}"),
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            ListFooterTip(
                isLoading = false,
                isError = uiState.error,
                hasMore = false,
                itemsIsEmpty = !uiState.hasItems && !uiState.loading,
            )
        }
    }
}

/**
 * 星期分割标题。
 *
 * 今天的标题用主文字色 + 加粗，其余用次级色 —— 用户扫一眼就能定位「今天」那一栏。
 *
 * @param group 该分组（含星期、条目数、是否今天）。
 */
@Composable
private fun WeekdayDivider(group: BangumiCalendarGroup) {
    Text(
        modifier = Modifier.fillMaxWidth(),
        text = group.title,
        textAlign = TextAlign.Start,
        style = MaterialTheme.typography.titleMedium.copy(lineHeight = 18.sp),
        color =
            if (group.isToday) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
}
