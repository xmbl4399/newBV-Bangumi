package dev.frost819.newbv.app.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.frost819.newbv.data.datastore.Prefs

/*
 * 网格列数的统一读取入口。
 *
 * 抽成 composable 的原因：
 * 1. 「界面设置 → 视频网格列数 / 番剧网格列数」改动后要**立即**反映到已打开的列表页，
 *    因此必须走 `Prefs` 的 Flow + `collectAsState`，而不是在组合时读一次快照；
 * 2. 十余个页面都要用同一份逻辑，集中在这里避免每个页面各写一遍 Flow 订阅。
 */

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
