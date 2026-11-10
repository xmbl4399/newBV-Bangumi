package dev.frost819.newbv.bangumiapi.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 星期枚举（Bangumi 日历接口的 `weekday.id`，`1` = 周一 … `7` = 周日）。
 *
 * 与 `java.time.DayOfWeek` 的 `value` 完全对齐（`MONDAY=1`），因此可直接用
 * `LocalDate.now().dayOfWeek.value` 取到今日对应的 [id]，无需手工映射。
 *
 * @property id 接口返回的星期编号（1~7）。
 * @property cn 中文名（星期一 …）。
 * @property shortCn 短名（周一 …），用于分组标题。
 */
enum class BangumiWeekday(
    val id: Int,
    val cn: String,
    val shortCn: String,
) {
    Monday(1, "星期一", "周一"),
    Tuesday(2, "星期二", "周二"),
    Wednesday(3, "星期三", "周三"),
    Thursday(4, "星期四", "周四"),
    Friday(5, "星期五", "周五"),
    Saturday(6, "星期六", "周六"),
    Sunday(7, "星期日", "周日"),
    ;

    companion object {
        /**
         * 从接口的 `weekday.id` 解析，未知值回退 [Monday]。
         *
         * @param id 星期编号（1~7）。
         * @return 对应星期。
         */
        fun fromId(id: Int): BangumiWeekday = entries.find { it.id == id } ?: Monday

        /**
         * 按「今日倒序」排出 7 天的展示顺序。
         *
         * 语义是**以今天为起点向前回溯**：今天周三 → 周三、周二、周一、周日、周六、周五、周四。
         * 这样用户打开这一页时，「今天在播什么」永远排在第一位，往后翻依次是
         * 「昨天播过什么」「更早播过什么」，符合「追番时先看今天、再补前几天」的直觉。
         *
         * 用**循环减法**而不是简单的 `sortedByDescending`：后者会在今天为周一（id=1）时
         * 把周日排到最后，而正确结果是周一、周日、周六…（跨周继续回溯）。
         *
         * @param today 今天的星期。
         * @return 长度恒为 7 的顺序列表，首元素是 [today]。
         */
        fun orderFrom(today: BangumiWeekday): List<BangumiWeekday> =
            (0 until 7).map { offset ->
                // Kotlin 的 % 对负数返回负数，故先 +7 再取模两次保证落在 1~7
                fromId(((today.id - 1 - offset) % 7 + 7) % 7 + 1)
            }
    }
}

/**
 * Bangumi 条目上的收藏统计（日历接口返回，`GET /calendar`）。
 *
 * @property doing 「在看」人数，是很有效的热门度信号。
 */
@Serializable
data class BangumiCollection(
    val doing: Int = 0,
)

/**
 * Bangumi 日历接口返回的单个条目（`GET /calendar` 的 `items[]` 元素）。
 *
 * 与 [BangumiSubject]（`/v0/subjects` 的列表项）**字段并不相同**，这是两个不同端点：
 * - 日历有 `air_date` / `air_weekday` / `collection.doing`，但**没有** `date` / `eps` /
 *   `tags` / `meta_tags`；
 * - 即日历只能回答「这部番周几播」，不能回答「更新到第几话」。
 *
 * 因此归一化到 [BangumiItem] 时，`eps` 置 null、`tags` 留空，仅在卡片上展示
 * 标题/封面/评分/在看人数。
 *
 * @property id 条目 ID。
 * @property name 原名（日文/英文）。
 * @property nameCn 中文名，可能为空串。
 * @property summary 简介，本方案不使用（体积大且列表页不展示）。
 * @property airDate 首播日期 `yyyy-MM-dd`。
 * @property airWeekday 星期编号（1~7），与 [BangumiWeekday.id] 同义。
 * @property rating 评分（复用 `/v0/subjects` 的同名字段结构）。
 * @property images 封面各档位。
 * @property collection 收藏统计。
 */
@Serializable
data class BangumiCalendarSubject(
    val id: Int = 0,
    val name: String = "",
    @SerialName("name_cn") val nameCn: String = "",
    val summary: String = "",
    @SerialName("air_date") val airDate: String = "",
    @SerialName("air_weekday") val airWeekday: Int = 0,
    val rating: BangumiRating = BangumiRating(),
    val images: BangumiImages = BangumiImages(),
    val collection: BangumiCollection = BangumiCollection(),
)

/**
 * 日历接口的星期分组（`GET /calendar` 数组的一个元素）。
 *
 * @property weekday 分组对应的星期。
 * @property items 该天在播的番剧。
 */
@Serializable
data class BangumiCalendarDay(
    val weekday: BangumiCalendarWeekday = BangumiCalendarWeekday(),
    val items: List<BangumiCalendarSubject> = emptyList(),
)

/**
 * 日历分组的星期描述（接口原样返回 `{en, cn, ja, id}`）。
 *
 * @property en 英文名（Mon）。
 * @property cn 中文名（星期一）。
 * @property ja 日文名（月耀日）。
 * @property id 星期编号（1~7）。
 */
@Serializable
data class BangumiCalendarWeekday(
    val en: String = "",
    val cn: String = "",
    val ja: String = "",
    val id: Int = 0,
)

/**
 * 已按「今日倒序」排好的单日番剧分组，供 UI 直接渲染。
 *
 * @property weekday 该组对应的星期。
 * @property items 该天在播的番剧（已归一化）。
 * @property isToday 是否为今天，UI 据此高亮标题。
 */
data class BangumiCalendarGroup(
    val weekday: BangumiWeekday,
    val items: List<BangumiItem>,
    val isToday: Boolean = false,
) {
    /** 分组标题，形如「周三 · 今天 · 12部」。 */
    val title: String
        get() =
            buildString {
                append(weekday.shortCn)
                if (isToday) append(" · 今天")
                append(" · ").append(items.size).append("部")
            }
}
