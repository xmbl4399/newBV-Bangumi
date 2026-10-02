package dev.frost819.newbv.app.ui.component

import dev.frost819.newbv.bangumiapi.entity.BangumiCategory
import dev.frost819.newbv.data.datastore.HomeTopNavItem

/**
 * 首页顶部 Tab 项。
 *
 * 将 data 模块的 [HomeTopNavItem] 映射为 app 层 [TopNavItem]，
 * 提供显示名称。
 *
 * @property item 原始 [HomeTopNavItem] 枚举。
 */
data class HomeTabItem(
    val item: HomeTopNavItem,
) : TopNavItem {
    override val displayName: String =
        when (item) {
            HomeTopNavItem.Dynamics -> "动态"
            HomeTopNavItem.Recommend -> "推荐"
            HomeTopNavItem.Popular -> "热门"
            HomeTopNavItem.TvAnime -> "TV动画"
            HomeTopNavItem.AnimeMovie -> "其他动画"
            HomeTopNavItem.JpDrama -> "日剧"
            HomeTopNavItem.WesternDrama -> "欧美剧"
            HomeTopNavItem.ChineseDrama -> "华语剧"
            HomeTopNavItem.KoreanDrama -> "韩剧"
            HomeTopNavItem.Movie -> "电影"
        }
}

/**
 * 首页 Tab 对应的 Bangumi 浏览分类。
 *
 * 文案与顺序照搬 blbl-Bangumi 的首页 tab
 * （推荐 / 热门 / TV动画 / 其他动画 / 日剧 / 欧美剧 / 华语剧 / 韩剧 / 电影）。
 * B 站原生的动态 / 推荐 / 热门三个 Tab 不属于 Bangumi 数据，
 * 因此映射为 null，由 `HomeContent` 交给各自的 Screen 处理。
 */
val HomeTopNavItem.bangumiCategory: BangumiCategory?
    get() =
        when (this) {
            HomeTopNavItem.TvAnime -> BangumiCategory.TvAnime
            HomeTopNavItem.AnimeMovie -> BangumiCategory.AnimeMovie
            HomeTopNavItem.JpDrama -> BangumiCategory.JpDrama
            HomeTopNavItem.WesternDrama -> BangumiCategory.WesternDrama
            HomeTopNavItem.ChineseDrama -> BangumiCategory.ChineseDrama
            HomeTopNavItem.KoreanDrama -> BangumiCategory.KoreanDrama
            HomeTopNavItem.Movie -> BangumiCategory.Movie
            HomeTopNavItem.Dynamics, HomeTopNavItem.Recommend, HomeTopNavItem.Popular -> null
        }
