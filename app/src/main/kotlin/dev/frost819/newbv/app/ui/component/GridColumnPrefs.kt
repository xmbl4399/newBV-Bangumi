package dev.frost819.newbv.app.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import dev.frost819.newbv.data.datastore.Prefs

/*
 * 网格列数与网格留白的统一口径。
 *
 * 抽成 composable 的原因：
 * 1. 「界面设置 → 视频网格列数 / 番剧网格列数」改动后要**立即**反映到已打开的列表页，
 *    因此必须走 `Prefs` 的 Flow + `collectAsState`，而不是在组合时读一次快照；
 * 2. 十余个页面都要用同一份逻辑，集中在这里避免每个页面各写一遍 Flow 订阅。
 */

/**
 * 视频卡片网格的留白口径（十余个网格页共用，勿逐页各写一套）。
 *
 * **18dp 是实测调出来的值**：原先四周与列间距都是 24dp，在 720P 档（1dp = 1px）下
 * 单边留白 24px + 3 道 24px 列缝共吃掉 120px，4 列时每张封面只剩 170px 宽。
 * 收到 18dp 后每张封面回到 182px（约 +7%），标题能从两行收成更少折行，
 * 同时仍保留足够的外沿呼吸感 —— 再小会让最左/最右的卡片贴住屏幕边缘，
 * 焦点框（3dp 边框）看起来像被裁切。
 */
object GridSpacing {
    /** 网格四周内边距（含左右边缘留白与顶部标题下的间距）。 */
    val contentPadding = 18.dp

    /** 卡片之间的水平间隙（列与列之间）。 */
    val horizontal = 18.dp
}

/**
 * B 站视频网格列数（每行卡片数）。
 *
 * 跟随「界面设置 → 视频网格列数」实时变化；作用于首页（推荐/热门/动态）、
 * 个人页（收藏/历史/稍后再看/追番）、直播、PGC 番剧等视频卡片网格。
 *
 * @return 每行卡片数（3~8）。
 */
@Composable
fun rememberVideoGridColumns(): Int {
    val columns by Prefs.videoGridColumnsFlow.collectAsState()
    return columns.columns
}

/**
 * 番剧（Bangumi）封面网格列数（每行封面数）。
 *
 * 跟随「界面设置 → 番剧网格列数」实时变化，只作用于二改新增的 Bangumi 分类页。
 *
 * @return 每行封面数（3~8）。
 */
@Composable
fun rememberBangumiGridColumns(): Int {
    val columns by Prefs.bangumiGridColumnsFlow.collectAsState()
    return columns.columns
}
