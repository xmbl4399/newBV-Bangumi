package dev.frost819.newbv.bangumiapi

import dev.frost819.newbv.bangumiapi.entity.BangumiCalendarSubject
import dev.frost819.newbv.bangumiapi.entity.BangumiItem
import dev.frost819.newbv.bangumiapi.entity.BangumiSubject
import dev.frost819.newbv.bangumiapi.tag.BangumiTagPicker

/**
 * 把接口原始条目 [BangumiSubject] 归一化为 UI 可直接消费的 [BangumiItem]。
 *
 * 归一化规则（全部由实测数据驱动，见 `bangumi-v0-api-guide.md`）：
 * - 标题：`name_cn` 为空时回退 `name`；
 * - 封面：固定取 `common`（r/400）—— **v0 与 p1 通路的档位命名不一致**
 *   （v0 `medium`=r/800、p1 `medium`=r/200），只有 `common` 两通路行为一致；
 * - 评分：`> 0.0` 才算有评分（接口无评分时给 0.0 而非 null）；
 * - tag：并集去重后按白名单分级取 ≤2 个；
 * - `meta_tags`：保序去重后原样保留（供上层做地区筛选，如韩剧）。
 */
object BangumiNormalizer {
    /**
     * 归一化单条条目。
     *
     * @param subject 接口返回的原始条目。
     * @return 归一化后的条目；[BangumiSubject.id] 为 0 等非法数据由调用方过滤。
     */
    fun normalize(subject: BangumiSubject): BangumiItem {
        val pool = BangumiTagPicker.buildPool(subject)
        return BangumiItem(
            id = subject.id,
            title = subject.nameCn.ifBlank { subject.name },
            date = subject.date,
            eps = subject.eps.takeIf { it > 0 },
            score = subject.rating.score,
            votes = subject.rating.total,
            cover = subject.images.common.ifBlank { subject.images.large },
            tags = BangumiTagPicker.pick(pool),
            // 服务端 meta_tags 存在重复元素（如 ["TV","TV","日本","日本"]），必须保序去重
            metaTags = subject.metaTags.filter { it.isNotBlank() }.distinct(),
        )
    }

    /**
     * 归一化日历接口的条目（`GET /calendar` 的 `items[]`）。
     *
     * 与 [normalize] 的差异来自两个端点的字段差异（见 [BangumiCalendarSubject]）：
     * - 日期取 `air_date` 而不是 `date`；
     * - **`eps` 置 null**：日历不返回集数，硬填 0 会让卡片显示「0话」这类错误信息；
     * - **`tags` 留空**：日历不返回用户投票词，白名单无处可取；
     * - `metaTags` 留空（同理，日历没有 `meta_tags`）。
     *
     * @param subject 日历接口返回的原始条目。
     * @return 归一化后的条目。
     */
    fun normalizeCalendar(subject: BangumiCalendarSubject): BangumiItem =
        BangumiItem(
            id = subject.id,
            title = subject.nameCn.ifBlank { subject.name },
            date = subject.airDate,
            // 日历不返回集数：null 表示未知，UI 据此不显示集数角标
            eps = null,
            score = subject.rating.score,
            votes = subject.rating.total,
            cover = subject.images.common.ifBlank { subject.images.large },
            // 日历不返回投票词，无题材 tag 可展示
            tags = emptyList(),
            metaTags = emptyList(),
        )
}
