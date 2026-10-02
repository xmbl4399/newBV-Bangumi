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

/**
 * new BV 应用入口。
 *
 * 初始化顺序：
 * 1. Hilt 依赖注入（由 [HiltAndroidApp] 自动处理）
 * 2. [Prefs] 偏好设置初始化（阻塞读取 DataStore 首帧）
 * 3. **界面缩放按屏幕宽度自动初始化**（仅首次启动，见 [initDensityByScreenWidth]）
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

        // 根据用户设置启用崩溃上传，并尝试上传上次崩溃未发送的日志
        crashUploader.enabled = Prefs.crashReportEnabled
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
    }

    /**
     * 首次启动时按屏幕分辨率决定界面缩放，之后一律沿用该值。
     *
     * [Prefs.density] 的语义是 Compose 的 density（1dp = N px），不是"倍率"：
     * - 720P（1280×720）取 2 会让逻辑宽度只剩 **640dp**，TV 版式被撑爆、一行放不下几个卡片；
     * - 1080P（1920×1080）取 2 得到 **960dp**，才是正常的 TV 逻辑宽度。
     *
     * 因此规则是：**低于 1080P 取 1x，1080P 及以上取 2x**。
     *
     * 判据取**屏幕长边**而不是 `widthPixels`：手机竖屏时 widthPixels 只有 1224，
     * 但它和横屏一样是 1080P 级屏幕，用宽度判会把手机误判成 1x。
     *
     * **只在首次启动执行一次**（靠 [Prefs.densityInitialized] 标记）。标记一旦写上，
     * 后续启动直接 return，用户在「界面设置 → 界面缩放」里选的值得以保留，
     * 不会每次开 App 又被屏幕分辨率覆盖回去。
     */
    private fun initDensityByScreenWidth() {
        if (Prefs.densityInitialized) return
        val metrics = resources.displayMetrics
        val longSidePx = maxOf(metrics.widthPixels, metrics.heightPixels)
        val density = if (longSidePx >= FHD_LONG_SIDE_PX) 2f else 1f
        Prefs.density = density
        Prefs.densityInitialized = true
        logger.info {
            "density auto-initialized: ${metrics.widthPixels}x${metrics.heightPixels} -> density=$density"
        }
    }

    private companion object {
        /** 1080P 长边像素数。达到即用 2x，否则（720P 等）退回 1x。 */
        const val FHD_LONG_SIDE_PX = 1920
    }
}
