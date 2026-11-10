package dev.frost819.newbv.app

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import dev.frost819.newbv.app.data.danmaku.DanmakuFilterRepository
import dev.frost819.newbv.app.network.HttpServer
import dev.frost819.newbv.app.util.CacheManager
import dev.frost819.newbv.biliapi.http.BiliHttpApi
import dev.frost819.newbv.biliapi.repositories.AuthRepository
import dev.frost819.newbv.biliapi.repositories.ChannelRepository
import dev.frost819.newbv.core.log.CrashHandler
import dev.frost819.newbv.core.log.CrashUploader
import dev.frost819.newbv.core.log.Loggers
import dev.frost819.newbv.data.datastore.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.roundToInt

/**
 * new BV 应用入口。
 *
 * 初始化顺序：
 * 1. Hilt 依赖注入（由 [HiltAndroidApp] 自动处理）
 * 2. [Prefs] 偏好设置初始化（阻塞读取 DataStore 首帧）
 * 3. **界面缩放按屏幕分辨率自适应**（720P = 1.0 基准，见 [initDensityByScreenWidth]）
 * 4. [CrashHandler] 全局崩溃处理（通过 Hilt 注入，构造时自动 install）
 * 5. [HttpServer] 本地日志管理服务器（通过 Hilt 注入，按需启动）
 */
@HiltAndroidApp
class BVApplication : Application() {
    private val logger = Loggers.get("BVApplication")

    @Inject
    lateinit var dataStore: DataStore<Preferences>

    @Inject
    lateinit var crashHandler: CrashHandler

    @Inject
    lateinit var crashUploader: CrashUploader

    @Inject
    lateinit var httpServer: HttpServer

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var channelRepository: ChannelRepository

    @Inject
    lateinit var danmakuFilterRepository: DanmakuFilterRepository

    /**
     * 播放器与 CDN 测速共用的 OkHttpClient。
     *
     * 用 [Provider] 延迟解析：client 构建包含系统 CA 库读取与 SSLContext 初始化，
     * 放在启动时的 IO 线程预热，避免首次进播放器时在主线程做这件事。
     */
    @Inject
    lateinit var playerHttpClientProvider: Provider<OkHttpClient>

    override fun onCreate() {
        super.onCreate()

        Prefs.init(dataStore)
        initDensityByScreenWidth()

        val buvid3 = Prefs.buvid3
        val deviceCookies = Prefs.deviceCookies
        val sessData = Prefs.sessData
        val biliJct = Prefs.biliJct
        val mid = Prefs.uid.takeIf { it > 0 }
        val accessToken = Prefs.accessToken

        authRepository.buvid3 = buvid3
        authRepository.deviceCookies = deviceCookies
        authRepository.sessionData = sessData
        authRepository.biliJct = biliJct
        authRepository.mid = mid
        authRepository.accessToken = accessToken
        BiliHttpApi.init(
            buvid3 = buvid3,
            deviceCookies = deviceCookies,
            sessData = sessData,
            biliJct = biliJct,
            mid = mid,
            accessToken = accessToken,
        )

        // 账号云端弹幕屏蔽规则：启动时后台预热，播放器/直播只读缓存（登录态由仓库自行判断）
        danmakuFilterRepository.prefetch()

        if (accessToken.isNotBlank() && Prefs.buvid.isNotBlank()) {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                channelRepository.initDefaultChannel(accessToken, Prefs.buvid)
            }
        }

        if (!Prefs.buvid3FromSpi) {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                val spiResult = BiliHttpApi.fetchBuvid3FromSpi()
                if (spiResult != null) {
                    Prefs.buvid3 = spiResult.buvid3
                    Prefs.deviceCookies = spiResult.deviceCookies
                    Prefs.buvid3FromSpi = true
                    authRepository.buvid3 = spiResult.buvid3
                    authRepository.deviceCookies = spiResult.deviceCookies
                    BiliHttpApi.buvid3 = spiResult.buvid3
                    BiliHttpApi.deviceCookies = spiResult.deviceCookies
                }
            }
        }

        @Suppress("UNUSED_EXPRESSION")
        crashHandler

        // 已启用上传时，尝试上传上次崩溃未发送的日志；开关状态由 CrashUploader 实时读取
        if (crashUploader.canUpload()) {
            CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                crashUploader.uploadPendingCrashLogs()
            }
        }

        logger.info { "Application started" }

        // 图片磁盘缓存上限：开启自动清理且设置了阈值时按阈值限制，否则不限制。
        // Coil 在每次写入时自动 LRU 淘汰超限条目（缓存满阈值自动清理）。
        val diskCacheMaxBytes =
            if (Prefs.cacheAutoClean && Prefs.cacheThreshold > 0) {
                Prefs.cacheThreshold * CacheManager.BYTES_PER_MB
            } else {
                CacheManager.UNLIMITED_DISK_CACHE_BYTES
            }
        val imageDiskCache =
            DiskCache
                .Builder()
                .directory(
                    File(cacheDir, CacheManager.IMAGE_CACHE_DIR).absolutePath.toPath(),
                ).maxSizeBytes(diskCacheMaxBytes)
                .build()

        coil3.SingletonImageLoader.setSafe {
            ImageLoader
                .Builder(this)
                .crossfade(true)
                .memoryCachePolicy(coil3.request.CachePolicy.ENABLED)
                .diskCachePolicy(coil3.request.CachePolicy.ENABLED)
                .diskCache(imageDiskCache)
                .components {
                    add(OkHttpNetworkFetcherFactory(OkHttpClient()))
                }.build()
        }

        // 启动时检查缓存阈值，超限自动 LRU 清理（检查时机 = App 启动 + 缓存写入后）
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            CacheManager(this@BVApplication).checkCache()
        }

        // 预热播放器 OkHttpClient（构建 SSLContext/KeyStore 较慢，放到 IO 线程）。
        // 预热后单例已就绪，首次进播放器时不再阻塞主线程。
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            playerHttpClientProvider.get()
        }
    }

    /**
     * 按屏幕分辨率自适应界面缩放，**以 720P 为 1.0 基准**。
     *
     * [Prefs.density] 的语义是 Compose 的 density（1dp = N px），不是"倍率"。
     * 换算目的是让**逻辑宽度恒定**：无论物理分辨率多少，屏幕都是同一个
     * 1280dp 宽的版面，这样 TV 版式（网格列数、卡片尺寸）在所有设备上一致。
     *
     * 公式：`density = 屏幕长边px / 1280`，即
     * - 720P（1280×720）→ 1.0，逻辑宽度 1280dp（基准）
     * - 1080P（1920×1080）→ 1.5，逻辑宽度 1280dp
     * - 4K（3840×2160）→ 3.0，逻辑宽度 1280dp
     * - 480P（854×480）→ 约 0.67，逻辑宽度仍约 1280dp
     *
     * 判据取**屏幕长边**而不是 `widthPixels`：手机竖屏时 widthPixels 只有 1224，
     * 但它和横屏一样是 1080P 级屏幕，用宽度判会算错。
     *
     * **每次启动都重算**（旧实现只在首启写一次，导致换设备/改分辨率后沿用错误值）。
     * 但若用户在「界面设置 → 界面缩放」里手动改过（[Prefs.densityInitialized] 为 true
     * 即表示已被用户接管），则尊重用户选择、不再覆盖。
     */
    private fun initDensityByScreenWidth() {
        if (Prefs.densityInitialized) return
        val metrics = resources.displayMetrics
        val longSidePx = maxOf(metrics.widthPixels, metrics.heightPixels)
        // 以 720P 的长边（1280px）为 1.0 基准；保留一位小数，避免浮点噪声导致重建
        val raw = longSidePx.toFloat() / BASE_LONG_SIDE_PX
        val density = (raw * 10f).roundToInt() / 10f
        Prefs.density = density.coerceIn(MIN_DENSITY, MAX_DENSITY)
        logger.info {
            "density auto-adapted: ${metrics.widthPixels}x${metrics.heightPixels} -> density=${Prefs.density}"
        }
    }

    private companion object {
        /** 720P 屏幕长边像素数，作为 1.0 缩放的基准。 */
        const val BASE_LONG_SIDE_PX = 1280f

        /** 缩放下限：再小封面会糊成一片。 */
        const val MIN_DENSITY = 0.5f

        /** 缩放上限：8K 级别，再大没有实际设备。 */
        const val MAX_DENSITY = 5f
    }
}
