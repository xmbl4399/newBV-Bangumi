package dev.frost819.newbv.bangumiapi.entity

/**
 * Bangumi 接口地址选项（设置页「BGM 接口设置」）。
 *
 * 官方 `api.bgm.tv` 在国内偶发 DNS 污染 / 被墙，社区反代可作首选；
 * 这里提供的只是**首选**地址，实际请求仍会自动在「官方 → 反代」之间兜底换源，
 * 详见 [dev.frost819.newbv.bangumiapi.http.BangumiHttpApi]。
 *
 * 反代取值与 [dev.frost819.newbv.bangumiapi.http.BangumiHttpApi.MIRROR_BASE_URLS]
 * **必须逐字一致**（有单测守着，避免两处各写一份导致漂移）。
 *
 * @property baseUrl 接口根地址；**空串代表「官方优先」**，与偏好项语义一致。
 */
enum class BangumiApiSource(
    val baseUrl: String,
) {
    /** 官方 `api.bgm.tv`（留空即官方优先，与 PiliPlus-Bangumi 的默认语义相同）。 */
    Official(""),

    /** 社区反代 bgm.retr0.xyz（带路径前缀）。 */
    Retr0("https://bgm.retr0.xyz/8d7db5cae"),

    /** 社区反代 bgmapi.anibt.net。 */
    Anibt("https://bgmapi.anibt.net"),
    ;

    companion object {
        /**
         * 从偏好里的地址串解析出选项；无法识别（含用户手填）时回退到 [Official]。
         *
         * @param baseUrl 偏好中保存的地址。
         * @return 匹配的选项。
         */
        fun fromBaseUrl(baseUrl: String): BangumiApiSource {
            val normalized = baseUrl.trim().trimEnd('/')
            return entries.firstOrNull { it.baseUrl == normalized } ?: Official
        }
    }
}
