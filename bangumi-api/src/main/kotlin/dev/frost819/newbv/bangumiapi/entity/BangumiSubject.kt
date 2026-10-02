package dev.frost819.newbv.bangumiapi.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Bangumi 条目封面各档位 URL。
 *
 * 接口为每个条目返回 5 档尺寸。注意各档位的像素宽度在不同通路上命名不一致
 * （同一档位名在 v0 / p1 通路下分辨率可能差 4 倍），
 * 因此仅使用两通路行为一致的 [common]（r/400）作为列表封面，
 * 避免换通路后封面清晰度突变。
 */
@Serializable
data class BangumiImages(
    val large: String = "",
    val common: String = "",
    val medium: String = "",
    val small: String = "",
    val grid: String = "",
)

/**
 * Bangumi 条目评分。
 *
 * @property score 评分，**无评分时为 0.0 而非 null**，展示前需判断 `> 0`。
 * @property rank 全站排名，无数据时为 0。
 * @property total 投票人数（显示用 "N 人评分"）。
 */
@Serializable
data class BangumiRating(
    val score: Double = 0.0,
    val rank: Int = 0,
    val total: Int = 0,
)

/**
 * Bangumi 条目上的用户投票 tag。
 *
 * @property name 词名（如 "奇幻"）。
 * @property count 该条目上此词的票数，用于排序（票数高者优先显示）。
 * @property totalCount 全站总票数，本方案不使用。
 */
@Serializable
data class BangumiTagVote(
    val name: String = "",
    val count: Int = 0,
    @SerialName("total_count") val totalCount: Int = 0,
)

/**
 * Bangumi v0 列表接口返回的单个条目（`GET /v0/subjects` 的 `data[]` 元素）。
 *
 * 这是**列表接口**的形态：一次请求即带全量 [tags] 与 [metaTags]，
 * 无需再逐条拉详情（实测详情接口的 tag 与列表项逐字节一致）。
 *
 * @property id 条目 ID。
 * @property name 原名（日文/英文）。
 * @property nameCn 中文名，可能为空串。
 * @property date 放送日期，格式 `yyyy-MM-dd`，可能为空串（未定档）。
 * @property eps 话数；0 表示接口未给出。
 * @property images 封面各档位。
 * @property rating 评分。
 * @property tags 用户投票词，接口按 count 降序但不保证，使用前显式排序。
 * @property metaTags 服务端结构化摘要；**存在重复元素**（如 `["TV","TV","日本","日本"]`），使用前必须去重。
 */
@Serializable
data class BangumiSubject(
    val id: Int = 0,
    val name: String = "",
    @SerialName("name_cn") val nameCn: String = "",
    val date: String = "",
    val eps: Int = 0,
    val images: BangumiImages = BangumiImages(),
    val rating: BangumiRating = BangumiRating(),
    val tags: List<BangumiTagVote> = emptyList(),
    @SerialName("meta_tags") val metaTags: List<String> = emptyList(),
)

/**
 * Bangumi v0 列表接口的分页响应体。
 *
 * @property total **总条数**（不是页数），用于判断是否翻页到底。
 * @property limit 本次请求的 limit 回显。
 * @property offset 本次请求的 offset 回显。
 * @property data 当前页条目列表。
 */
@Serializable
data class BangumiSubjectPage(
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
    val data: List<BangumiSubject> = emptyList(),
)
