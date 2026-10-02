package dev.frost819.newbv.bangumiapi.tag

import dev.frost819.newbv.bangumiapi.entity.BangumiSubject

/**
 * 从候选词池中挑选要展示的流派 tag。
 *
 * 核心洞察：**不能按票数取 Top N**。票数最高的永远是 `2026年1月`、`MAPPA`、`TV`、`日本`
 * 这类元信息，内容词（题材）票数天然偏低 —— 按票数取 2 个，卡片上会全是「TV / 日本」。
 * 因此必须**先按品类筛，再在同品类内按票数排**。
 */
object BangumiTagPicker {
    /** 卡片上最多显示的 tag 数量。 */
    const val MAX_TAG_COUNT: Int = 2

    /**
     * 构造候选词池。
     *
     * 并集的理由：`meta_tags` 不是「票数 Top N」，而是服务端为塞进平台/地区词
     * 而精挑的摘要，**会把题材词挤掉**（典型案例：某条目 `meta_tags` 只有
     * `TV 日本 原创`，而 `tags` 里有票数 54 的「科幻」），所以两边都要。
     *
     * @param subject 接口原始条目。
     * @return 去重后的候选词，投票词在前（票数降序），`meta_tags` 在后。
     */
    fun buildPool(subject: BangumiSubject): List<String> {
        val voted =
            subject.tags
                .filter { it.name.isNotBlank() }
                // 接口虽已按 count 降序，但不赌接口顺序，显式排序
                .sortedByDescending { it.count }
                .map { it.name }
        val meta = subject.metaTags.filter { it.isNotBlank() }
        return (voted + meta).distinct()
    }

    /**
     * 从候选词池中分级挑选要展示的 tag。
     *
     * @param pool [buildPool] 的输出。
     * @return 最多 [MAX_TAG_COUNT] 个词，题材优先、来源/受众补位；池中无命中词时为空列表。
     */
    fun pick(pool: List<String>): List<String> {
        val tier1 = pool.filter { it in BangumiTagWhitelist.TAG_TIER1 }
        val tier2 = pool.filter { it in BangumiTagWhitelist.TAG_TIER2 }
        return (tier1 + tier2).take(MAX_TAG_COUNT)
    }
}
