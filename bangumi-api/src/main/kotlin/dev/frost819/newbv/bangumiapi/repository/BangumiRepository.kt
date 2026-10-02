package dev.frost819.newbv.bangumiapi.repository

import dev.frost819.newbv.bangumiapi.BangumiNormalizer
import dev.frost819.newbv.bangumiapi.cache.BangumiDiskCache
import dev.frost819.newbv.bangumiapi.entity.BangumiCategory
import dev.frost819.newbv.bangumiapi.entity.BangumiItem
import dev.frost819.newbv.bangumiapi.entity.BangumiMonthResult
import dev.frost819.newbv.bangumiapi.http.BangumiHttpApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Bangumi 条目数据仓库。
 *
 * 数据源：`GET https://api.bgm.tv/v0/subjects?type=&cat=&year=&month=&limit=100&offset=N`，
 * **取数口径与排序规则整体照搬 blbl-Bangumi 的 `core/api/BangumiApi.kt`**，不自行发明：
 * - **不带 `sort` 参数**。`sort=rank` 与 `year+month` 组合在服务端不稳定，
 *   实测 2026-08 的 `cat=3`（剧场版）无 `sort` 返回 11 条、带 `sort=rank` 只回 3 条（数据截断）；
 * - 拉全后**本地按评分降序**（无评分按日期垫底），所有分类口径一致；
 * - **不做详情补拉**。详情接口的 `tags` 与列表项逐字节一致，补拉只会让请求数变成 N 倍；
 * - **磁盘缓存由 [cache] 承担**（可空注入）：以「大类+子分类+年+月」为键，
 *   当季/未来月份 6 小时、历史月份 30 天，见 `BangumiDiskCache`。
 *
 * @param httpApi 可注入的接口客户端（单测注入 MockK 或 MockEngine 客户端）。
 * @param cache 磁盘缓存，null 表示不缓存（纯单测/无缓存环境）。
 */
class BangumiRepository(
    private val httpApi: BangumiHttpApi = BangumiHttpApi(),
    private val cache: BangumiDiskCache? = null,
) {
    /**
     * 拉取某分类某年某月的全部条目（自动翻页）。
     *
     * 单月条数通常 < 100，因此绝大多数月份**一次请求即完成**。
     *
     * 「其他动画」([BangumiCategory.mergesMultipleCats]) 会合并
     * `cat=5(WEB) ∪ cat=2(OVA) ∪ cat=3(剧场版)` 三个子分类；
     * 其中**单个子分类失败不影响其余两个**，三者全失败才判定本月失败。
     *
     * @param category 浏览分类。
     * @param year 年份（4 位）。
     * @param month 月份（1~12）。
     * @param hideNoScore 是否剔除无评分条目（设置页「隐藏无评分条目」）。照搬 blbl-Bangumi：
     *   在**排序之后**按 `score > 0` 过滤，因此「隐藏」只会让列表变短，不会打乱顺序。
     * @return 该月条目，已按评分降序（无评分按日期垫底）。
     * @throws java.io.IOException 网络异常或超时。
     */
    suspend fun browseYearMonth(
        category: BangumiCategory,
        year: Int,
        month: Int,
        hideNoScore: Boolean = false,
    ): List<BangumiItem> {
        val raw =
            if (category.mergesMultipleCats) {
                fetchMergedAnimeMovie(type = category.type, year = year, month = month)
            } else {
                fetchMonth(type = category.type, cat = category.cat, year = year, month = month)
            }
        // 韩剧无官方分类：从电视剧大类里按 meta_tags 含「韩国」二次筛选
        val scoped = if (category.korean) raw.filter { KOREAN_META_TAG in it.metaTags } else raw
        // 「隐藏无评分」照搬 blbl-Bangumi：在**排序之后**剔除无评分条目（score <= 0）
        return if (hideNoScore) scoped.filter { it.hasScore } else scoped
    }

    /**
     * 「其他动画」的单月数据 = 三个非电视放送子分类合并去重。
     *
     * WEB（网络播，现代新番大量在此）/ OVA（碟片发售）/ 剧场版（电影）。
     *
     * @param type 条目大类。
     * @param year 年份。
     * @param month 月份。
     * @return 合并去重后的条目，按评分降序。
     * @throws java.io.IOException 三个子分类**全部**失败时抛出首个异常。
     */
    private suspend fun fetchMergedAnimeMovie(
        type: Int,
        year: Int,
        month: Int,
    ): List<BangumiItem> {
        val outcomes = EXTRA_ANIME_CATS.map { cat -> runCatching { fetchMonth(type, cat, year, month) } }
        val succeeded = outcomes.mapNotNull { it.getOrNull() }
        if (succeeded.isEmpty()) {
            outcomes.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        }
        return succeeded.reduceOrNull { acc, list -> mergeByScore(acc, list) }.orEmpty()
    }

    /**
     * 拉取单个 `type+cat` 的整月条目（自动翻页），并按评分降序排序。
     *
     * **磁盘缓存插在这一层**（网络边界）：命中即直接返回，不再发请求；
     * 未命中才走网络，成功后再落盘。缓存键不含 `hideNoScore` / 韩剧过滤条件，
     * 因此那两个条件变化时不会让缓存失效（过滤在调用方做）。
     *
     * @param type 条目大类。
     * @param cat 子分类。
     * @param year 年份。
     * @param month 月份。
     * @return 已排序的整月条目。
     */
    private suspend fun fetchMonth(
        type: Int,
        cat: Int,
        year: Int,
        month: Int,
    ): List<BangumiItem> {
        cache?.get(type = type, cat = cat, year = year, month = month)?.let { return it }

        // 用 LinkedHashMap 按 id 去重：接口翻页时偶发重复条目
        val collected = LinkedHashMap<Int, BangumiItem>()
        var offset = 0
        while (true) {
            val page = httpApi.getSubjects(type = type, cat = cat, year = year, month = month, limit = PAGE_LIMIT, offset = offset)
            page.data
                .filter { it.id > 0 }
                .forEach { collected[it.id] = BangumiNormalizer.normalize(it) }

            // 不足一页说明已到底
            if (page.data.size < PAGE_LIMIT) break
            offset += PAGE_LIMIT
            // 与 blbl-Bangumi 一致的上限保护：极端情况下不无限翻页
            if (offset > MAX_OFFSET) break
        }
        val sorted = collected.values.sortedWith(ITEM_ORDER)
        cache?.put(type = type, cat = cat, year = year, month = month, items = sorted)
        return sorted
    }

    /**
     * 合并两组条目并按 id 去重，结果按评分降序。
     *
     * 与 blbl-Bangumi 的 `mergeByScore` 同语义：先来的同 id 条目优先（`putIfAbsent`）。
     *
     * @param a 第一组。
     * @param b 第二组。
     * @return 合并去重并排序后的条目。
     */
    private fun mergeByScore(
        a: List<BangumiItem>,
        b: List<BangumiItem>,
    ): List<BangumiItem> {
        val byId = LinkedHashMap<Int, BangumiItem>()
        (a + b).forEach { byId.putIfAbsent(it.id, it) }
        return byId.values.sortedWith(ITEM_ORDER)
    }

    /**
     * 加载某分类某年的全年条目，**每完成一个月就推一条结果**。
     *
     * 月份默认倒序（12 月 → 1 月）入队，让当季新作优先返回；但**推送顺序不保证**
     * （并发拉取，先完成先推），需要稳定顺序请自行按 [BangumiMonthResult.month] 排序。
     * 单月失败**不影响**其它月份：失败月推 [BangumiMonthResult.error]，调用方自行统计。
     *
     * @param category 浏览分类。
     * @param year 年份。
     * @param months 要加载的月份，默认 12→1。
     * @param concurrency 并发上限。api.bgm.tv 未标注限流，取 3 以兼顾速度与礼貌。
     * @param hideNoScore 是否剔除无评分条目（设置页「隐藏无评分条目」，默认开启）。
     * @return 逐月推送的结果流；收集完成即代表全年加载结束。
     */
    fun loadYear(
        category: BangumiCategory,
        year: Int,
        months: List<Int> = DEFAULT_MONTHS,
        concurrency: Int = DEFAULT_CONCURRENCY,
        hideNoScore: Boolean = false,
    ): Flow<BangumiMonthResult> =
        channelFlow {
            val semaphore = Semaphore(concurrency)
            months.forEach { month ->
                launch {
                    semaphore.withPermit {
                        emitMonth(
                            category = category,
                            year = year,
                            month = month,
                            hideNoScore = hideNoScore,
                        )
                    }
                }
            }
            // Ktor 的响应解码发生在**调用方上下文**（不是引擎线程），
            // 若不加这一行，单月 100 KB 级响应的 JSON 解析会落在 Main 线程上
            // （blbl 上实测造成 `Skipped 155 frames`≈2.6s 的掉帧）。
        }.flowOn(Dispatchers.IO)

    /**
     * 加载单个月份并推送结果（内部实现，失败被收敛进 [BangumiMonthResult]）。
     */
    private suspend fun ProducerScope<BangumiMonthResult>.emitMonth(
        category: BangumiCategory,
        year: Int,
        month: Int,
        hideNoScore: Boolean,
    ) {
        val result =
            runCatching {
                browseYearMonth(
                    category = category,
                    year = year,
                    month = month,
                    hideNoScore = hideNoScore,
                )
            }
        send(
            BangumiMonthResult(
                year = year,
                month = month,
                items = result.getOrDefault(emptyList()),
                error = result.exceptionOrNull(),
            ),
        )
    }

    companion object {
        /** 单页条数。v0 接口 limit 上限为 100，传 200 会 HTTP 400。 */
        const val PAGE_LIMIT: Int = 100

        /** 翻页偏移上限，超出即停（照搬 blbl-Bangumi 的保护值）。 */
        const val MAX_OFFSET: Int = 500

        /** 全年加载的默认并发上限。 */
        const val DEFAULT_CONCURRENCY: Int = 3

        /** 默认加载月份：倒序，让当季（年末）月份优先返回。 */
        val DEFAULT_MONTHS: List<Int> = (12 downTo 1).toList()

        /** 「其他动画」合并的子分类：WEB / OVA / 剧场版。 */
        val EXTRA_ANIME_CATS: List<Int> = listOf(5, 2, 3)

        /** 韩剧判定用的 meta_tags 词。 */
        const val KOREAN_META_TAG: String = "韩国"

        /**
         * 列表排序：评分降序，无评分（0.0）沉底，同分按放送日期升序。
         *
         * 与 blbl-Bangumi 的 `fetchYearMonth` 内排序一致 —— 动画原按 rank 排，
         * 但 browse 接口不返回 `rank`，且 `rank+month` 服务端排序不稳定，
         * 因此所有分类统一用评分口径。
         */
        val ITEM_ORDER: Comparator<BangumiItem> =
            compareByDescending<BangumiItem> { if (it.score > 0.0) it.score else NO_SCORE_KEY }
                .thenBy { it.date }

        /** 无评分条目的排序键，必须小于任何真实评分（评分为 0~10）。 */
        private const val NO_SCORE_KEY: Double = -1.0
    }
}
