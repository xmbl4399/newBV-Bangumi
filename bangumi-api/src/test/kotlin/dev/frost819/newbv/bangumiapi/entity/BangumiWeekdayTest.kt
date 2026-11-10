package dev.frost819.newbv.bangumiapi.entity

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [BangumiWeekday] 的单元测试。
 *
 * 重点是 [BangumiWeekday.orderFrom] 的「今日倒序」语义，尤其**跨周回溯**：
 * 今天为周一时，正确结果是 周一、周日、周六…（而不是把周日排到最后）。
 */
class BangumiWeekdayTest {
    @Test
    fun `id follows ISO 8601 with monday as 1 and sunday as 7`() {
        assertThat(BangumiWeekday.Monday.id).isEqualTo(1)
        assertThat(BangumiWeekday.Sunday.id).isEqualTo(7)
        // 与 java.time.DayOfWeek.value 对齐，调用方可直接用 LocalDate 取
        assertThat(BangumiWeekday.Wednesday.id).isEqualTo(3)
    }

    @Test
    fun `fromId resolves each weekday`() {
        assertThat(BangumiWeekday.fromId(1)).isEqualTo(BangumiWeekday.Monday)
        assertThat(BangumiWeekday.fromId(7)).isEqualTo(BangumiWeekday.Sunday)
    }

    @Test
    fun `fromId falls back to monday for unknown id`() {
        assertThat(BangumiWeekday.fromId(0)).isEqualTo(BangumiWeekday.Monday)
        assertThat(BangumiWeekday.fromId(99)).isEqualTo(BangumiWeekday.Monday)
    }

    @Test
    fun `orderFrom places today first`() {
        val order = BangumiWeekday.orderFrom(BangumiWeekday.Wednesday)

        assertThat(order.first()).isEqualTo(BangumiWeekday.Wednesday)
        assertThat(order).hasSize(7)
    }

    @Test
    fun `orderFrom goes backwards through the week for wednesday`() {
        // 今天周三 → 周三、周二、周一、周日、周六、周五、周四
        assertThat(BangumiWeekday.orderFrom(BangumiWeekday.Wednesday))
            .containsExactly(
                BangumiWeekday.Wednesday,
                BangumiWeekday.Tuesday,
                BangumiWeekday.Monday,
                BangumiWeekday.Sunday,
                BangumiWeekday.Saturday,
                BangumiWeekday.Friday,
                BangumiWeekday.Thursday,
            ).inOrder()
    }

    @Test
    fun `orderFrom wraps across the week start when today is monday`() {
        // 今天周一 → 周一、周日、周六、周五、周四、周三、周二
        // 这是简单的 sortedByDescending 会算错的用例（它会把周日丢到末尾）
        assertThat(BangumiWeekday.orderFrom(BangumiWeekday.Monday))
            .containsExactly(
                BangumiWeekday.Monday,
                BangumiWeekday.Sunday,
                BangumiWeekday.Saturday,
                BangumiWeekday.Friday,
                BangumiWeekday.Thursday,
                BangumiWeekday.Wednesday,
                BangumiWeekday.Tuesday,
            ).inOrder()
    }

    @Test
    fun `orderFrom when today is sunday yields a full descending run`() {
        assertThat(BangumiWeekday.orderFrom(BangumiWeekday.Sunday))
            .containsExactly(
                BangumiWeekday.Sunday,
                BangumiWeekday.Saturday,
                BangumiWeekday.Friday,
                BangumiWeekday.Thursday,
                BangumiWeekday.Wednesday,
                BangumiWeekday.Tuesday,
                BangumiWeekday.Monday,
            ).inOrder()
    }

    @Test
    fun `orderFrom always contains every weekday exactly once`() {
        BangumiWeekday.entries.forEach { today ->
            val order = BangumiWeekday.orderFrom(today)

            assertThat(order).hasSize(7)
            assertThat(order.toSet()).hasSize(7)
            assertThat(order.first()).isEqualTo(today)
        }
    }

    @Test
    fun `group title marks today and counts items`() {
        val group =
            BangumiCalendarGroup(
                weekday = BangumiWeekday.Wednesday,
                items = emptyList(),
                isToday = true,
            )

        assertThat(group.title).isEqualTo("周三 · 今天 · 0部")
    }

    @Test
    fun `group title omits today marker for other days`() {
        val group =
            BangumiCalendarGroup(
                weekday = BangumiWeekday.Monday,
                items = emptyList(),
                isToday = false,
            )

        assertThat(group.title).isEqualTo("周一 · 0部")
    }
}
