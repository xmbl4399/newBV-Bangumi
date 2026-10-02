package dev.frost819.newbv.bangumiapi.tag

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.bangumiapi.entity.BangumiSubject
import dev.frost819.newbv.bangumiapi.entity.BangumiTagVote
import org.junit.jupiter.api.Test

/**
 * [BangumiTagPicker] 的单元测试。
 *
 * 验证候选词池构造（并集 / 去重 / 票数排序）与分级挑选（题材优先、来源补位、上限 2 个）。
 */
class BangumiTagPickerTest {
    private fun vote(
        name: String,
        count: Int,
    ) = BangumiTagVote(name = name, count = count)

    @Test
    fun `buildPool merges voted tags and meta tags with votes first`() {
        // Given：meta_tags 带服务端重复 bug（TV/日本 各重复一次）
        val subject =
            BangumiSubject(
                tags = listOf(vote("科幻", 54), vote("战斗", 29)),
                metaTags = listOf("TV", "TV", "日本", "日本", "原创"),
            )

        // When
        val pool = BangumiTagPicker.buildPool(subject)

        // Then：投票词在前，meta 词在后，整体去重
        assertThat(pool).containsExactly("科幻", "战斗", "TV", "日本", "原创").inOrder()
    }

    @Test
    fun `buildPool sorts voted tags by count desc even if api order is wrong`() {
        // Given：接口乱序返回（不赌接口已排序）
        val subject =
            BangumiSubject(
                tags = listOf(vote("日常", 10), vote("奇幻", 90), vote("校园", 40)),
            )

        // When
        val pool = BangumiTagPicker.buildPool(subject)

        // Then
        assertThat(pool).containsExactly("奇幻", "校园", "日常").inOrder()
    }

    @Test
    fun `buildPool drops blank names`() {
        val subject =
            BangumiSubject(
                tags = listOf(vote("", 99), vote("奇幻", 5)),
                metaTags = listOf("", "  ", "原创"),
            )

        val pool = BangumiTagPicker.buildPool(subject)

        assertThat(pool).containsExactly("奇幻", "原创").inOrder()
    }

    @Test
    fun `pick prefers tier1 genre over tier2 source`() {
        // Given：票数上 meta 的「原创」并不比题材词低，但题材词必须优先
        val pool = listOf("原创", "TV", "科幻", "战斗")

        // When
        val picked = BangumiTagPicker.pick(pool)

        // Then
        assertThat(picked).containsExactly("科幻", "战斗").inOrder()
    }

    @Test
    fun `pick fills second slot with tier2 when only one genre word exists`() {
        val pool = listOf("TV", "日本", "漫画改", "悬疑")

        val picked = BangumiTagPicker.pick(pool)

        assertThat(picked).containsExactly("悬疑", "漫画改").inOrder()
    }

    @Test
    fun `pick ignores tier3 platform and region words`() {
        // Given：池里只有平台/地区词 —— 按设计宁缺毋滥
        val pool = listOf("Web", "WEB", "日本", "TV", "OAD")

        val picked = BangumiTagPicker.pick(pool)

        assertThat(picked).isEmpty()
    }

    @Test
    fun `pick caps result at two tags`() {
        val pool = listOf("奇幻", "战斗", "恋爱", "日常", "校园")

        val picked = BangumiTagPicker.pick(pool)

        assertThat(picked).hasSize(BangumiTagPicker.MAX_TAG_COUNT)
    }

    @Test
    fun `pick returns empty list for empty pool`() {
        assertThat(BangumiTagPicker.pick(emptyList())).isEmpty()
    }
}
