package dev.frost819.newbv.bangumiapi.entity

import java.util.Locale
import kotlinx.serialization.Serializable

/** Bangumi 条目大类：动画。 */
const val BANGUMI_TYPE_ANIME: Int = 2

/** Bangumi 条目大类：三次元（剧集 / 电影）。 */
const val BANGUMI_TYPE_REAL: Int = 6

/**
 * Bangumi 浏览分类（`type` + `cat` 组合）。
 *
 * 参数取值**照搬 blbl-Bangumi 的 `BangumiCalendarMode`**
 * （`feature/home/BangumiCalendarFragment.kt`），不自行推测：
 * bgm 对 `type=6`（三次元）的 `cat` 划分没有公开文档，
 * 6001（电视剧）/ 6002（电影）等值均由该项目实测得出。
 *
 * 两个特例：
 * - [AnimeMovie]「其他动画」= `cat=5(WEB) ∪ cat=2(OVA) ∪ cat=3(剧场版)` 合并去重，
 *   因此 [cat] 只是占位值，取数走
 *   [dev.frost819.newbv.bangumiapi.repository.BangumiRepository.browseYearMonth] 的合并分支；
 * - [KoreanDrama] 无官方分类，从 `cat=6001` 里按 `meta_tags` 含「韩国」过滤，
 *   即 [korean] 为 true 时结果需二次筛选。
 *
 * @property type 条目大类（2 = 动画，6 = 三次元）。
 * @property cat 子分类。
 * @property displayName 中文显示名（与 blbl-Bangumi 的 tab 文案一致）。
 * @property korean 是否需要按 `meta_tags` 含「韩国」二次过滤。
 */
enum class BangumiCategory(
    val type: Int,
    val cat: Int,
    val displayName: String,
    val korean: Boolean = false,
) {
    /** TV 动画：纯 `cat=1`，WEB 已拆到 [AnimeMovie]。 */
    TvAnime(BANGUMI_TYPE_ANIME, cat = 1, displayName = "TV动画"),

    /** 其他动画：WEB ∪ OVA ∪ 剧场版（见类文档）。 */
    AnimeMovie(BANGUMI_TYPE_ANIME, cat = 3, displayName = "其他动画"),

    /** 日剧：`type=6` 下的 `cat=1`。 */
    JpDrama(BANGUMI_TYPE_REAL, cat = 1, displayName = "日剧"),

    /** 欧美剧：`type=6` 下的 `cat=2`。 */
    WesternDrama(BANGUMI_TYPE_REAL, cat = 2, displayName = "欧美剧"),

    /** 华语剧：`type=6` 下的 `cat=3`。 */
    ChineseDrama(BANGUMI_TYPE_REAL, cat = 3, displayName = "华语剧"),

    /** 韩剧：`cat=6001` + `meta_tags` 含「韩国」。 */
    KoreanDrama(BANGUMI_TYPE_REAL, cat = 6001, displayName = "韩剧", korean = true),

    /** 电影：`type=6` 下的 `cat=6002`。 */
    Movie(BANGUMI_TYPE_REAL, cat = 6002, displayName = "电影"),
    ;

    /** 是否由多个子分类合并而成（当前仅 [AnimeMovie]）。 */
    val mergesMultipleCats: Boolean
        get() = this == AnimeMovie
}

/**
 * 已归一化的 Bangumi 条目，供 UI 直接消费。
 *
 * 与 [BangumiSubject]（接口原始形态）的区别：
 * - 标题已做 `name_cn` 优先回退；
 * - 封面已固定选用 `common` 档；
 * - tag 已去重并按白名单分级过滤到 ≤2 个。
 *
 * @property id 条目 ID。
 * @property title 展示标题（中文名优先）。
 * @property date 放送日期 `yyyy-MM-dd`，空串表示未定档。
 * @property eps 话数，null 表示未知。
 * @property score 评分，**0.0 表示无评分**。
 * @property votes 评分人数。
 * @property cover 封面 URL（`common` 档）。
 * @property tags 流派 tag（≤2 个，可能为空 —— 冷门条目两字段都为空是常态）。
 * @property metaTags 服务端结构化摘要原始数组（已保序去重）。
 *   卡片不展示，仅供**地区筛选**使用（如韩剧按含「韩国」过滤），
 *   与 blbl-Bangumi 的 `BangumiCalendarItem.metaTags` 同义。
 *
 * 标注 [Serializable] 是为了让 `BangumiDiskCache` 能把整月结果落盘复用
 * （派生属性 `hasScore` / `scoreText` / `year` 是 `get()`，不参与序列化）。
 */
@Serializable
data class BangumiItem(
    val id: Int,
    val title: String,
    val date: String,
    val eps: Int?,
    val score: Double,
    val votes: Int,
    val cover: String,
    val tags: List<String>,
    val metaTags: List<String> = emptyList(),
) {
    /** 是否有有效评分（接口无评分时给 0.0，不能显示成 "0.0 分"）。 */
    val hasScore: Boolean
        get() = score > 0.0

    /** 一位小数的评分文本，仅在有评分时有意义。 */
    val scoreText: String
        get() = String.format(Locale.US, "%.1f", score)

    /** 从日期中取年份，解析失败返回 null。 */
    val year: Int?
        get() = date.take(4).toIntOrNull()
}

/**
 * 单个月份的加载结果。
 *
 * [error] 非 null 表示该月失败，[items] 为空；一个月份失败不应影响其它月份。
 *
 * @property year 年份。
 * @property month 月份（1~12）。
 * @property items 该月条目，已按 `放送日期倒序` 排序。
 * @property error 失败原因，成功时为 null。
 */
data class BangumiMonthResult(
    val year: Int,
    val month: Int,
    val items: List<BangumiItem>,
    val error: Throwable? = null,
) {
    /** 该月是否成功加载。 */
    val isSuccess: Boolean
        get() = error == null
}
