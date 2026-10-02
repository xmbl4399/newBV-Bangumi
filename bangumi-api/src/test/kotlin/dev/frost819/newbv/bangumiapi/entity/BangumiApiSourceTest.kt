package dev.frost819.newbv.bangumiapi.entity

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.bangumiapi.http.BangumiHttpApi
import org.junit.jupiter.api.Test

/**
 * [BangumiApiSource] 的单元测试。
 *
 * 重点是**守住与 [BangumiHttpApi.MIRROR_BASE_URLS] 的一致性**：
 * 两处各写一份地址，任何一边改动而另一边没跟，设置页选中的「首选源」与
 * 实际兜底池就会悄悄错位。这里把它变成编译后立刻能发现的失败。
 */
class BangumiApiSourceTest {
    @Test
    fun `mirror entries stay in sync with http mirror list`() {
        val mirrors = BangumiApiSource.entries.filter { it != BangumiApiSource.Official }.map { it.baseUrl }

        assertThat(mirrors).containsExactlyElementsIn(BangumiHttpApi.MIRROR_BASE_URLS).inOrder()
    }

    @Test
    fun `official entry uses an empty string so it means "prefer official"`() {
        assertThat(BangumiApiSource.Official.baseUrl).isEmpty()
    }

    @Test
    fun `fromBaseUrl resolves every entry back to itself`() {
        BangumiApiSource.entries.forEach { source ->
            assertThat(BangumiApiSource.fromBaseUrl(source.baseUrl)).isEqualTo(source)
        }
    }

    @Test
    fun `fromBaseUrl trims whitespace and trailing slash`() {
        assertThat(BangumiApiSource.fromBaseUrl("  https://bgmapi.anibt.net/  "))
            .isEqualTo(BangumiApiSource.Anibt)
    }

    @Test
    fun `fromBaseUrl falls back to official for unknown or hand-typed urls`() {
        assertThat(BangumiApiSource.fromBaseUrl("https://api.bangumi.lol")).isEqualTo(BangumiApiSource.Official)
        assertThat(BangumiApiSource.fromBaseUrl("")).isEqualTo(BangumiApiSource.Official)
    }
}
