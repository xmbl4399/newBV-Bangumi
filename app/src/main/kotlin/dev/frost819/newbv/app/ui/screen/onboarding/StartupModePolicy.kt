package dev.frost819.newbv.app.ui.screen.onboarding

import dev.frost819.newbv.data.datastore.Prefs

/**
 * 首次启动引导的副作用落地（标清 / 高清模式）。
 *
 * 抽成顶层函数而不是写在 Composable 里，是为了能**脱离 Android 运行时单测**：
 * 引导页最重要的一条不变量是「写完这三个值以后再也不会回到引导页」，
 * 这个不变量必须能被测试守住，否则用户会每次开 App 都被拦一次。
 *
 * 写入顺序刻意固定为「先写两个档位、最后写 [Prefs.isOnboarded]」：
 * 引导页是否跳过只看 `isOnboarded`，把它放最后可以保证只要用户看到过主页，
 * 档位就一定已经落盘 —— 中途进程被杀时不会留下「已标记完成但档位没写」的状态。
 *
 * @param mode 用户选择或默认命中的模式。
 */
fun applyStartupMode(mode: StartupMode) {
    Prefs.density = mode.densityValue
    Prefs.densityInitialized = true
    Prefs.defaultQuality = mode.quality
    Prefs.isOnboarded = true
}

/**
 * 按屏幕长边推断应默认高亮的模式。
 *
 * 只用于**给引导页一个合理的默认选中项**，不是自动定档：用户仍可改成另一档。
 * 判据与旧版 `BVApplication.initDensityByScreenWidth` 一致 ——
 * 取长边（手机竖屏时 widthPixels 只有 1224，但它和横屏一样是 1080P 级屏幕，
 * 用宽度判会把手机误判成标清），达到 1080P 长边即默认高清。
 *
 * @param widthPixels 屏幕宽（像素）。
 * @param heightPixels 屏幕高（像素）。
 * @return 建议默认高亮的模式。
 */
fun defaultStartupModeFor(
    widthPixels: Int,
    heightPixels: Int,
): StartupMode {
    val longSidePx = maxOf(widthPixels, heightPixels)
    return if (longSidePx >= FHD_LONG_SIDE_PX) StartupMode.High else StartupMode.Standard
}

/** 1080P 长边像素数。达到即默认高清，否则（720P 等）默认标清。 */
private const val FHD_LONG_SIDE_PX = 1920
