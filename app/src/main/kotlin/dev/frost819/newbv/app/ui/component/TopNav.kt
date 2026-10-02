package dev.frost819.newbv.app.ui.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Tab
import androidx.tv.material3.TabDefaults
import androidx.tv.material3.TabRow
import androidx.tv.material3.TabRowDefaults
import androidx.tv.material3.TabRowScope
import androidx.tv.material3.Text
import dev.frost819.newbv.core.focus.touchClickable

/**
 * 顶部导航 Tab 栏。
 *
 * TV Material3 [TabRow] 封装，支持 D-Pad 焦点导航。
 * Tab 切换时触发 [onSelectedChanged]，点击同一 Tab 触发 [onClick]（用于刷新）。
 *
 * [isCompact] 用于「项多但每项短」的场景（如 Bangumi 年份栏 2006~今年共 21 项）：
 * 收窄内边距与项间距、缩小字号，整排压进屏幕，且**整体比普通模式小一号**
 * （普通 32dp/16dp/labelLarge ↔ 紧凑 26dp/10dp/labelMedium）。
 * 紧凑模式的上下留白恒为 2dp，不再随 [isLargePadding] 变化。
 *
 * **不要在 [TabRow] 外面再套 `Modifier.horizontalScroll`**：TV Material3 的 [TabRow]
 * 内部自带滚动容器，外层再给一层横向滚动会把「无限宽约束」传进去，
 * 直接抛 `IllegalStateException: Horizontally scrollable component was measured
 * with an infinity maximum width constraints`。项真的排不下时，由 [TabRow] 自己滚。
 *
 * ### 选中态的配色（曾被吐槽「墨绿字压在深色药丸上糊成一团」）
 *
 * 药丸填充由**框架的 [TabRowDefaults.PillIndicator] 绘制**（[Tab] 自己的 container 色被
 * 框架强制成透明），它的默认色是 `onSurface`（浅色主题下是深藏青）+ `secondaryContainer@40%`。
 * 若文字再单独取强调色 `secondary`（墨绿），就成了「深绿字 + 深色药丸」的低对比组合。
 *
 * 因此这里的规则是**填充与文字成对定义**，文字只取白或黑：
 *
 * | 状态 | 药丸填充 | 文字 |
 * | --- | --- | --- |
 * | 导航栏有焦点（选中项） | `secondary` 实心强调色 | `onSecondary`（浅色=白 / 深色=近黑） |
 * | 导航栏无焦点（选中项） | `secondary@14%` 浅强调色 | `onSurface`（近黑） |
 * | 未选中 | 无 | `onSurfaceVariant` |
 *
 * @param items Tab 项列表（已按首选项排序）。
 * @param selectedIndex 当前选中的 Tab 索引（由外部控制，用于导航返回后恢复）。
 * @param isLargePadding 内容区未获焦点时使用较大内边距。
 * @param isCompact 紧凑模式（小字号 + 窄间距 + 更小的上下留白）。
 * @param onSelectedChanged Tab 焦点切换回调。
 * @param onClick Tab 点击回调。
 */
@Composable
fun TopNav(
    modifier: Modifier = Modifier,
    items: List<TopNavItem>,
    selectedIndex: Int = 0,
    isLargePadding: Boolean,
    isCompact: Boolean = false,
    onSelectedChanged: (TopNavItem) -> Unit = {},
    onClick: (TopNavItem) -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }

    var selectedTabIndex by remember { mutableIntStateOf(selectedIndex) }

    // 初始焦点落在**进入时选中的那一项**，而不是永远落在第一项。
    // 焦点落在哪一项会立刻通过 onFocus 改写选中项 —— 若固定绑在第 0 项，
    // 用户在设置里选了「默认首页 Tab = 热门」也会被首页的第一项（动态）顶掉。
    // 只在首次组合时取值，之后不跟随选中变化，避免焦点被反复抢走。
    val initialFocusIndex = remember { selectedIndex }

    LaunchedEffect(selectedIndex) {
        selectedTabIndex = selectedIndex
    }

    // 紧凑模式（年份栏）本身栏位就矮，再用普通模式的上下留白会显得空 —— 见 KDoc。
    // 留 2dp 而不是 0：贴着分类栏与网格会显得"夹在中间"，2dp 只做视觉分隔、不撑高。
    val verticalPadding by animateDpAsState(
        targetValue =
            when {
                isCompact -> 2.dp
                isLargePadding -> 12.dp
                else -> 6.dp
            },
        label = "top-nav-padding",
    )

    // 药丸填充：有焦点时实心强调色，否则浅强调色（与 NavItemTab 的文字色成对）
    val pillColor = MaterialTheme.colorScheme.secondary
    val pillInactiveColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(12.dp, verticalPadding),
        horizontalArrangement = if (isCompact) Arrangement.Start else Arrangement.Center,
    ) {
        TabRow(
            modifier =
                Modifier
                    .focusRestorer(focusRequester),
            selectedTabIndex = selectedTabIndex,
            separator = { Spacer(modifier = Modifier.width(if (isCompact) 4.dp else 12.dp)) },
            indicator = { tabPositions, doesTabRowHaveFocus ->
                tabPositions.getOrNull(selectedTabIndex)?.let { currentTabPosition ->
                    TabRowDefaults.PillIndicator(
                        currentTabPosition = currentTabPosition,
                        doesTabRowHaveFocus = doesTabRowHaveFocus,
                        activeColor = pillColor,
                        inactiveColor = pillInactiveColor,
                    )
                }
            },
        ) {
            items.forEachIndexed { index, tab ->
                NavItemTab(
                    modifier = if (index == initialFocusIndex) Modifier.focusRequester(focusRequester) else Modifier,
                    topNavItem = tab,
                    selected = index == selectedTabIndex,
                    compact = isCompact,
                    onFocus = {
                        selectedTabIndex = index
                        onSelectedChanged(tab)
                    },
                    onClick = { onClick(tab) },
                )
            }
        }
    }
}

@Composable
private fun TabRowScope.NavItemTab(
    modifier: Modifier = Modifier,
    topNavItem: TopNavItem,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit,
) {
    // 文字色必须与 TopNav 里画的药丸填充成对，否则又会出现「同色系糊在一起」。
    // hasFocus 是 TabRowScope 的整行焦点状态，与药丸的 active/inactive 判据完全一致。
    val contentColor =
        when {
            selected && hasFocus -> MaterialTheme.colorScheme.onSecondary
            selected -> MaterialTheme.colorScheme.onSurface
            hasFocus -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

    Tab(
        modifier = modifier.touchClickable(onClick = onClick),
        selected = selected,
        onFocus = onFocus,
        onClick = onClick,
        colors =
            TabDefaults.pillIndicatorTabColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
                inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                selectedContentColor = MaterialTheme.colorScheme.onSurface,
                focusedContentColor = MaterialTheme.colorScheme.onSurface,
                focusedSelectedContentColor = MaterialTheme.colorScheme.onSecondary,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledSelectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
    ) {
        // 用固定高度的 Box 居中文字：直接对 Text 设 height 会把字形顶对齐，
        // 字体行高与剩余空间不等时文字就偏上，不同字体/字号下表现不一致。
        // 紧凑模式 26dp / 横向 10dp：比顶栏（32dp / 16dp / labelLarge）**整整小一号**，
        // 但比 22dp 那版舒展 —— 22dp 时字号还是 12sp，药丸却矮到像被压扁。
        Box(
            modifier =
                Modifier
                    .height(if (compact) 26.dp else 32.dp)
                    .padding(horizontal = if (compact) 10.dp else 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = topNavItem.displayName,
                color = contentColor,
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * 顶部导航项接口。
 */
interface TopNavItem {
    val displayName: String
}

private data class DummyTopNavItem(
    override val displayName: String,
) : TopNavItem

@Preview(showBackground = true)
@Composable
private fun TopNavPreview() {
    dev.frost819.newbv.core.theme.BVTheme {
        TopNav(
            items =
                listOf(
                    DummyTopNavItem("推荐"),
                    DummyTopNavItem("热门"),
                    DummyTopNavItem("动态"),
                ),
            isLargePadding = true,
        )
    }
}
