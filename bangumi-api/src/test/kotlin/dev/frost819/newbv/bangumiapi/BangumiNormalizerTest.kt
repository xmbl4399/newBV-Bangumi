package dev.frost819.newbv.bangumiapi

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.bangumiapi.entity.BangumiImages
import dev.frost819.newbv.bangumiapi.entity.BangumiRating
import dev.frost819.newbv.bangumiapi.entity.BangumiSubject
import dev.frost819.newbv.bangumiapi.entity.BangumiTagVote
import org.junit.jupiter.api.Test

/**
 * [BangumiNormalizer] 的单元测试。
 *
 * 验证标题回退、封面档位选择、无评分与无话数的处理，以及 tag 归一化串联。
 */
class BangumiNormalizerTest {
    private fun subject(
        id: Int = 443446,
        name: String = "地獄楽 第二季",
        nameCn: String = "地狱乐 第二季",
        date: String = "2026-01-11",
        eps: Int = 12,
        score: Double = 6.8,
        total: Int = 1203,
        images: BangumiImages = BangumiImages(common = "https://example.com/common.jpg", large = "https://example.com/large.jpg"),
        tags: List<BangumiTagVote> = emptyList(),
        metaTags: List<String> = emptyList(),
    ) = BangumiSubject(
        id = id,
        name = name,
        nameCn = nameCn,
        date = date,
        eps = eps,
        images = images,
        rating = BangumiRating(score = score, rank = 2345, total = total),
        tags = tags,
        metaTags = metaTags,
    )

    @Test
    fun `normalize prefers chinese name and keeps basic fields`() {
        val item = BangumiNormalizer.normalize(subject())

        assertThat(item.id).isEqualTo(443446)
        assertThat(item.title).isEqualTo("地狱乐 第二季")
        assertThat(item.date).isEqualTo("2026-01-11")
        assertThat(item.eps).isEqualTo(12)
        assertThat(item.score).isEqualTo(6.8)
        assertThat(item.votes).isEqualTo(1203)
        assertThat(item.year).isEqualTo(2026)
    }

    @Test
    fun `normalize falls back to original name when chinese name is blank`() {
        val item = BangumiNormalizer.normalize(subject(nameCn = ""))

        assertThat(item.title).isEqualTo("地獄楽 第二季")
    }

    @Test
    fun `normalize uses common cover tier`() {
        // Given：各档位都不同 —— 必须固定取 common（两通路行为一致的那档）
        val images =
            BangumiImages(
                large = "https://example.com/large.jpg",
                common = "https://example.com/common.jpg",
                medium = "https://example.com/medium.jpg",
                small = "https://example.com/small.jpg",
                grid = "https://example.com/grid.jpg",
            )

        val item = BangumiNormalizer.normalize(subject(images = images))

        assertThat(item.cover).isEqualTo("https://example.com/common.jpg")
    }

    @Test
    fun `normalize falls back to large cover when common is missing`() {
        val images = BangumiImages(common = "", large = "https://example.com/large.jpg")

        val item = BangumiNormalizer.normalize(subject(images = images))

        assertThat(item.cover).isEqualTo("https://example.com/large.jpg")
    }

    @Test
    fun `normalize treats zero score as unrated`() {
        // Given：接口无评分时给 0.0 而不是 null
        val item = BangumiNormalizer.normalize(subject(score = 0.0, total = 0))

        assertThat(item.score).isEqualTo(0.0)
        assertThat(item.hasScore).isFalse()
    }

    @Test
    fun `normalize formats score with one decimal`() {
        assertThat(BangumiNormalizer.normalize(subject(score = 7.0)).scoreText).isEqualTo("7.0")
        assertThat(BangumiNormalizer.normalize(subject(score = 9.75)).scoreText).isEqualTo("9.8")
    }

    @Test
    fun `normalize maps zero eps to null`() {
        val item = BangumiNormalizer.normalize(subject(eps = 0))

        assertThat(item.eps).isNull()
    }

    @Test
    fun `normalize picks tags from merged pool`() {
        // Given：题材词票数低于平台词，仍必须被选中
        val item =
            BangumiNormalizer.normalize(
                subject(
                    tags =
                        listOf(
                            BangumiTagVote(name = "2026年1月", count = 200),
                            BangumiTagVote(name = "TV", count = 150),
                            BangumiTagVote(name = "战斗", count = 40),
                        ),
                    metaTags = listOf("日本", "原创"),
                ),
            )

        assertThat(item.tags).containsExactly("战斗", "原创").inOrder()
    }

    @Test
    fun `normalize yields empty tags when pool has no whitelisted word`() {
        val item =
            BangumiNormalizer.normalize(
                subject(
                    tags = listOf(BangumiTagVote(name = "短片集", count = 12)),
                    metaTags = listOf("WEB"),
                ),
            )

        assertThat(item.tags).isEmpty()
    }
}
