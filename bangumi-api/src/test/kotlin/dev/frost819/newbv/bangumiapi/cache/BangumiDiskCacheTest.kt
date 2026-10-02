package dev.frost819.newbv.bangumiapi.cache

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.bangumiapi.entity.BangumiItem
import java.io.File
import java.util.Calendar
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * [BangumiDiskCache] 的单元测试。
 *
 * 用 JUnit 的 [TempDir] 提供真实临时目录 + 假时钟，覆盖读写往返、分级 TTL 过期、
 * 损坏文件降级、清空与统计。不触网、不依赖真实日期。
 */
class BangumiDiskCacheTest {
    @TempDir
    lateinit var tempDir: File

    /** 假时钟：固定为 2026-09-27，测试内自行拨动。 */
    private var now: Long = FIXED_NOW

    private fun cache(
        liveTtlMillis: Long = BangumiDiskCache.LIVE_TTL_MS,
        historyTtlMillis: Long = BangumiDiskCache.HISTORY_TTL_MS,
    ): BangumiDiskCache =
        BangumiDiskCache(
            rootDir = File(tempDir, BangumiDiskCache.DIR_NAME),
            liveTtlMillis = liveTtlMillis,
            historyTtlMillis = historyTtlMillis,
            clock = { now },
        )

    private fun item(id: Int) =
        BangumiItem(
            id = id,
            title = "中文$id",
            date = "2026-10-0$id",
            eps = 12,
            score = 8.5,
            votes = 321,
            cover = "cover$id",
            tags = listOf("奇幻", "战斗"),
            metaTags = listOf("日本"),
        )

    @Test
    fun `get returns null when nothing was stored`() {
        assertThat(cache().get(TYPE, CAT, CURRENT_YEAR, CURRENT_MONTH)).isNull()
    }

    @Test
    fun `put then get roundtrips every field`() {
        val cache = cache()
        cache.put(TYPE, CAT, CURRENT_YEAR, CURRENT_MONTH, listOf(item(1), item(2)))

        val restored = cache.get(TYPE, CAT, CURRENT_YEAR, CURRENT_MONTH)

        assertThat(restored).isEqualTo(listOf(item(1), item(2)))
    }

    @Test
    fun `cache keys are isolated per type cat year and month`() {
        val cache = cache()
        cache.put(TYPE, CAT, CURRENT_YEAR, CURRENT_MONTH, listOf(item(1)))

        assertThat(cache.get(TYPE, CAT, CURRENT_YEAR, CURRENT_MONTH - 1)).isNull()
        assertThat(cache.get(TYPE, CAT + 1, CURRENT_YEAR, CURRENT_MONTH)).isNull()
        assertThat(cache.get(TYPE + 1, CAT, CURRENT_YEAR, CURRENT_MONTH)).isNull()
        assertThat(cache.get(TYPE, CAT, CURRENT_YEAR - 1, CURRENT_MONTH)).isNull()
    }

    @Test
    fun `live months expire after the short ttl`() {
        val cache = cache(liveTtlMillis = 1_000L, historyTtlMillis = 10_000_000L)
        cache.put(TYPE, CAT, CURRENT_YEAR, CURRENT_MONTH, listOf(item(1)))

        now += 1_001L

        assertThat(cache.get(TYPE, CAT, CURRENT_YEAR, CURRENT_MONTH)).isNull()
    }

    @Test
    fun `future months count as live`() {
        val cache = cache(liveTtlMillis = 1_000L, historyTtlMillis = 10_000_000L)
        cache.put(TYPE, CAT, CURRENT_YEAR, 12, listOf(item(1)))

        now += 1_001L

        assertThat(cache.get(TYPE, CAT, CURRENT_YEAR, 12)).isNull()
    }

    @Test
    fun `past months survive the short ttl and expire only after the long one`() {
        val cache = cache(liveTtlMillis = 1_000L, historyTtlMillis = 10_000_000L)
        cache.put(TYPE, CAT, PAST_YEAR, 3, listOf(item(1)))

        now += 5_000_000L
        assertThat(cache.get(TYPE, CAT, PAST_YEAR, 3)).isNotNull()

        now += 5_000_001L
        assertThat(cache.get(TYPE, CAT, PAST_YEAR, 3)).isNull()
    }

    @Test
    fun `expired entry is deleted from disk`() {
        val cache = cache(liveTtlMillis = 1L, historyTtlMillis = 1L)
        cache.put(TYPE, CAT, PAST_YEAR, 3, listOf(item(1)))
        val file = File(File(tempDir, BangumiDiskCache.DIR_NAME), "t${TYPE}_c${CAT}_${PAST_YEAR}_3.json")
        assertThat(file.exists()).isTrue()

        now += 2L
        cache.get(TYPE, CAT, PAST_YEAR, 3)

        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `corrupted file degrades to a miss instead of throwing`() {
        val cache = cache()
        cache.put(TYPE, CAT, CURRENT_YEAR, CURRENT_MONTH, listOf(item(1)))
        File(File(tempDir, BangumiDiskCache.DIR_NAME), "t${TYPE}_c${CAT}_${CURRENT_YEAR}_${CURRENT_MONTH}.json")
            .writeText("{ 半截文件")

        assertThat(cache.get(TYPE, CAT, CURRENT_YEAR, CURRENT_MONTH)).isNull()
    }

    @Test
    fun `hit is served with a touched mtime so LRU keeps it`() {
        val cache = cache()
        cache.put(TYPE, CAT, PAST_YEAR, 3, listOf(item(1)))
        val file = File(File(tempDir, BangumiDiskCache.DIR_NAME), "t${TYPE}_c${CAT}_${PAST_YEAR}_3.json")
        file.setLastModified(1_000L)

        now += 60_000L
        assertThat(cache.get(TYPE, CAT, PAST_YEAR, 3)).isNotNull()

        assertThat(file.lastModified()).isEqualTo(now)
    }

    @Test
    fun `clear removes every cached file`() {
        val cache = cache()
        cache.put(TYPE, CAT, PAST_YEAR, 1, listOf(item(1)))
        cache.put(TYPE, CAT, PAST_YEAR, 2, listOf(item(2)))

        cache.clear()

        assertThat(cache.get(TYPE, CAT, PAST_YEAR, 1)).isNull()
        assertThat(cache.get(TYPE, CAT, PAST_YEAR, 2)).isNull()
    }

    @Test
    fun `sizeBytes counts what was written`() {
        val cache = cache()
        assertThat(cache.sizeBytes()).isEqualTo(0L)

        cache.put(TYPE, CAT, PAST_YEAR, 1, listOf(item(1), item(2)))

        assertThat(cache.sizeBytes()).isGreaterThan(0L)
    }

    @Test
    fun `hits and misses are counted`() {
        val cache = cache()
        cache.put(TYPE, CAT, PAST_YEAR, 1, listOf(item(1)))

        cache.get(TYPE, CAT, PAST_YEAR, 1)
        cache.get(TYPE, CAT, PAST_YEAR, 2)

        assertThat(cache.hits).isEqualTo(1L)
        assertThat(cache.misses).isEqualTo(1L)
    }

    private companion object {
        /** 测试基准时刻：2026-09-27 12:00 (GMT+8)。年份/月份由它派生，测试不随真实日期漂移。 */
        const val FIXED_NOW: Long = 1_790_481_600_000L

        const val TYPE: Int = 2
        const val CAT: Int = 1

        /** 假时钟所在年份。 */
        val CURRENT_YEAR: Int = yearOf(FIXED_NOW)

        /** 假时钟所在月份（1~12）。 */
        val CURRENT_MONTH: Int = monthOf(FIXED_NOW)

        /** 一个确定属于「历史月份」的年份。 */
        const val PAST_YEAR: Int = 2006

        fun yearOf(millis: Long): Int = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR)

        fun monthOf(millis: Long): Int =
            Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.MONTH) + 1
    }
}
