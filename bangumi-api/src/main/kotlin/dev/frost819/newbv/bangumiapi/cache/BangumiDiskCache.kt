package dev.frost819.newbv.bangumiapi.cache

import dev.frost819.newbv.bangumiapi.entity.BangumiItem
import java.io.File
import java.util.Calendar
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Bangumi 列表结果的本地磁盘缓存。
 *
 * 动机：一次「分类 + 年份」浏览要发 12 个请求（每月一个），其中最慢的当季月份
 * 响应体上百 KB，冷启动动辄数秒。落地到磁盘后，二次进入（含杀进程重启）
 * 直接读本地文件，只剩 JSON 解析开销。
 *
 * ## 缓存粒度
 *
 * 以**「条目大类 + 子分类 + 年 + 月」**为键，即 `GET /v0/subjects` 的入参四要素。
 * 存的是该月**已归一化并按评分排序**的完整条目列表：
 * - 「隐藏无评分」「韩剧按 meta_tags 过滤」都在**读取缓存之上**做，
 *   因此切换这两个条件不会让缓存失效；
 * - 「其他动画」由三个子分类合并，天然落成三份缓存，各自独立失效。
 *
 * ## 过期策略（分级 TTL）
 *
 * 月份数据的「新鲜度」差别很大 —— 往年月份的条目基本不会变，而当季/未来月份
 * 每周都在新增条目、累积评分：
 *
 * | 月份 | TTL | 理由 |
 * | --- | --- | --- |
 * | 当前年当前月及以后 | [liveTtlMillis]（6 小时） | 正在放送，条目与评分持续变化 |
 * | 往年及本年已过月份 | [historyTtlMillis]（30 天） | 条目集合已稳定，只是评分微调 |
 *
 * 过期判断基于**文件内记录的写入时间**（[CacheEntry.savedAt]），不是文件 mtime ——
 * mtime 被命中时的「触碰」行为改写，用于近似 LRU 淘汰（见 [get]）。
 *
 * ## 容错
 *
 * 缓存**永远不能成为新的故障点**：目录不可写、JSON 损坏、文件被系统清掉，
 * 一律降级为「未命中」而不是抛异常。
 *
 * 纯 JVM 实现（无 Android 依赖），目录由调用方注入。
 *
 * @param rootDir 缓存根目录（Android 侧传 `context.cacheDir/bangumi_api`）。
 * @param liveTtlMillis 当季/未来月份的存活时间（ms）。
 * @param historyTtlMillis 历史月份的存活时间（ms）。
 * @param clock 当前时间提供者（单测注入假时钟以验证过期）。
 */
class BangumiDiskCache(
    private val rootDir: File,
    private val liveTtlMillis: Long = LIVE_TTL_MS,
    private val historyTtlMillis: Long = HISTORY_TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** 命中次数（进程内累计，仅供诊断/日志）。 */
    private val hitCount = AtomicLong()

    /** 未命中次数（含过期、损坏、不存在）。 */
    private val missCount = AtomicLong()

    /** 进程内累计命中次数。 */
    val hits: Long
        get() = hitCount.get()

    /** 进程内累计未命中次数。 */
    val misses: Long
        get() = missCount.get()

    /**
     * 读取某月缓存。
     *
     * 命中时会「触碰」文件（更新 mtime），让 app 层缓存清理的按 mtime 近似 LRU 淘汰
     * 留下最近仍在使用的条目。
     *
     * @param type 条目大类。
     * @param cat 子分类。
     * @param year 年份。
     * @param month 月份。
     * @return 命中的条目列表；不存在 / 过期 / 损坏时返回 null（过期与损坏会顺手删除文件）。
     */
    fun get(
        type: Int,
        cat: Int,
        year: Int,
        month: Int,
    ): List<BangumiItem>? {
        val file = fileFor(type, cat, year, month)
        if (!file.isFile) {
            missCount.incrementAndGet()
            return null
        }
        val entry =
            runCatching { JSON.decodeFromString<CacheEntry>(file.readText()) }
                .getOrElse {
                    // 半截文件（上次写入中途被杀）或格式变更：删掉当未命中处理
                    file.delete()
                    missCount.incrementAndGet()
                    return null
                }
        if (clock() - entry.savedAt > ttlFor(year, month)) {
            file.delete()
            missCount.incrementAndGet()
            return null
        }
        file.setLastModified(clock())
        hitCount.incrementAndGet()
        return entry.items
    }

    /**
     * 写入某月缓存。
     *
     * 写失败（磁盘满、无权限）静默忽略 —— 缓存只是加速手段，不能阻断正常加载。
     *
     * @param type 条目大类。
     * @param cat 子分类。
     * @param year 年份。
     * @param month 月份。
     * @param items 该月**已归一化排序**的完整条目。
     */
    fun put(
        type: Int,
        cat: Int,
        year: Int,
        month: Int,
        items: List<BangumiItem>,
    ) {
        runCatching {
            rootDir.mkdirs()
            fileFor(type, cat, year, month)
                .writeText(JSON.encodeToString(CacheEntry(savedAt = clock(), items = items)))
        }
    }

    /**
     * 该月份适用的存活时长。
     *
     * 「现在」取 [clock] 而非系统时钟，保证单测能用假时钟断言分级 TTL
     * （否则测试结果会随真实日期漂移）。
     *
     * 用 [Calendar] 而非 `java.time`：模块 minSdk 21 且未开启 desugaring。
     *
     * @param year 年份。
     * @param month 月份（1~12）。
     * @return 存活时长（ms）。
     */
    internal fun ttlFor(
        year: Int,
        month: Int,
    ): Long {
        val now = Calendar.getInstance().apply { timeInMillis = clock() }
        val isLive = year > now.get(Calendar.YEAR) || (year == now.get(Calendar.YEAR) && month >= now.get(Calendar.MONTH) + 1)
        return if (isLive) liveTtlMillis else historyTtlMillis
    }

    /** 清空全部缓存（供「清空缓存」入口调用）。 */
    fun clear() {
        runCatching { rootDir.listFiles()?.forEach { it.delete() } }
    }

    /** 当前缓存占用字节数。 */
    fun sizeBytes(): Long = runCatching { rootDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)

    /** 某月对应的缓存文件。 */
    private fun fileFor(
        type: Int,
        cat: Int,
        year: Int,
        month: Int,
    ): File = File(rootDir, "t${type}_c${cat}_${year}_${month}.json")

    companion object {
        /** 缓存目录名（放在 `context.cacheDir` 下，随系统/「清空缓存」一起回收）。 */
        const val DIR_NAME: String = "bangumi_api"

        /** 当季与未来月份的存活时间：6 小时。 */
        const val LIVE_TTL_MS: Long = 6L * 60 * 60 * 1000

        /** 历史月份的存活时间：30 天。 */
        const val HISTORY_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000

        /**
         * 缓存文件的 JSON 配置。
         *
         * 只开 `ignoreUnknownKeys`：缓存是纯内部格式，但仍要求对**旧版本写下的文件**
         * 保持宽容（新增字段不该让整月缓存直接作废）。
         */
        private val JSON: Json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
    }
}

/**
 * 单个缓存文件的载荷。
 *
 * @property savedAt 写入时刻（epoch ms），过期判断以此为准。
 * @property items 该月的完整条目列表。
 */
@Serializable
internal data class CacheEntry(
    val savedAt: Long,
    val items: List<BangumiItem>,
)
