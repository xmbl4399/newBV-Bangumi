package dev.frost819.newbv.app.ui.component.videocard

import dev.frost819.newbv.bangumiapi.entity.BangumiItem

/**
 * Bangumi 番剧卡片数据。
 *
 * 与 [SeasonCardData] 的差异：Bangumi 条目**可能没有评分**（接口给 0.0），
 * 也**可能没有任何 tag**（冷门条目两字段皆空是常态，占比约 13%），
 * 因此两者都是可空的，卡片必须能容忍只有一个标题的"裸卡"。
 *
 * @property id 条目 ID。
 * @property title 标题（中文名优先）。
 * @property cover 封面 URL（`common` 档，r/400）。
 * @property scoreText 一位小数评分文本，null 表示无评分。
 * @property isHighScore 是否高分（≥ [HIGH_SCORE_THRESHOLD]，卡片用金色徽章强调）。
 * @property tags 流派 tag（≤2 个，可能为空）。
 * @property epCount 总话数，null 表示接口未给出。
 */
data class BangumiCardData(
    val id: Int,
    val title: String,
    val cover: String,
    val scoreText: String? = null,
    val isHighScore: Boolean = false,
    val tags: List<String> = emptyList(),
    val epCount: Int? = null,
) {
    /** 是否有话数可显示。 */
    val hasEpCount: Boolean
        get() = epCount != null && epCount > 0

    companion object {
        /** 高分阈值，≥ 该值的评分用金色徽章。 */
        const val HIGH_SCORE_THRESHOLD: Double = 7.0

        /**
         * 从仓库模型转换。
         *
         * 显示开关照搬 blbl-Bangumi：**话数仅 TV 动画显示**（剧场/剧集/电影没有「集」的概念），
         * **流派 tag 仅动画向显示**（三次元分类的 tag 是地区/平台词，显示出来是噪音）。
         *
         * @param item 归一化后的 Bangumi 条目。
         * @param showTags 是否带出流派 tag。
         * @param showEpisodes 是否带出话数。
         */
        fun from(
            item: BangumiItem,
            showTags: Boolean = true,
            showEpisodes: Boolean = true,
        ): BangumiCardData =
            BangumiCardData(
                id = item.id,
                title = item.title,
                cover = item.cover,
                scoreText = item.scoreText.takeIf { item.hasScore },
                isHighScore = item.score >= HIGH_SCORE_THRESHOLD,
                tags = if (showTags) item.tags else emptyList(),
                epCount = if (showEpisodes) item.eps else null,
            )
    }
}
