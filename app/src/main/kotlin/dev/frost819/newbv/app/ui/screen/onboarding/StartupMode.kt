package dev.frost819.newbv.app.ui.screen.onboarding

import dev.frost819.newbv.data.datastore.Resolution

/**
 * 首次启动可选的播放模式（标清 / 高清）。
 *
 * 一个模式同时决定两件事，这是产品约定的「一键定档」：
 * - **界面缩放**（[densityValue]，写入 `Prefs.density`）：标清 1x、高清 2x；
 * - **默认画质**（[quality]，写入 `Prefs.defaultQuality`）：标清 720P、高清 1080P。
 *
 * 之所以要用户选而不是像旧版那样按屏幕宽度自动判断：在 720P 屏幕上按宽度判只会得到 1x，
 * 但用户完全可能希望用 2x 的密排小卡界面（逻辑宽度更大、一屏看到的卡片更多），
 * 反过来也一样。分辨率的物理值不该替用户做这个取舍，所以把选择权交出来，
 * 选完之后「界面设置 → 界面缩放」「播放设置 → 默认分辨率」仍可各自单独微调。
 *
 * @property densityValue 写入 `Prefs.density` 的 Compose density（1dp = N px）。
 * @property quality 写入 `Prefs.defaultQuality` 的默认画质。
 */
enum class StartupMode(
    val densityValue: Float,
    val quality: Resolution,
) {
    /**
     * 标清模式：界面缩放 1x + 默认画质 720P。
     *
     * 1x 下 1dp = 1px，逻辑分辨率与物理分辨率一致，元素更大更易点按。
     */
    Standard(
        densityValue = 1f,
        quality = Resolution.R720P,
    ),

    /**
     * 高清模式：界面缩放 2x + 默认画质 1080P。
     *
     * 2x 是 TV 上的常规档：1080P 屏得到 960dp 逻辑宽度。
     */
    High(
        densityValue = 2f,
        quality = Resolution.R1080P,
    ),
}
