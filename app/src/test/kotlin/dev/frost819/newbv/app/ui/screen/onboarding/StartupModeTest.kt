package dev.frost819.newbv.app.ui.screen.onboarding

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.data.datastore.Resolution
import org.junit.jupiter.api.Test

/**
 * [StartupMode] 与 [defaultStartupModeFor] 的单元测试。
 *
 * 只覆盖纯逻辑：档位 → 缩放/画质的映射必须与产品约定一致（1x + 720P / 2x + 1080P），
 * 以及默认高亮项的屏幕判据（长边 ≥ 1920 才算高清）。
 *
 * 不覆盖 [applyStartupMode] 的写盘：它依赖 [dev.frost819.newbv.data.datastore.Prefs]
 * 单例，而 Prefs 的读写路径已由 `data` 模块的 PrefsTest 覆盖（含 isOnboarded 往返）。
 */
class StartupModeTest {
    @Test
    fun standardMode_uses_1x_scale_and_720p_quality() {
        assertThat(StartupMode.Standard.densityValue).isEqualTo(1f)
        assertThat(StartupMode.Standard.quality).isEqualTo(Resolution.R720P)
    }

    @Test
    fun highMode_uses_2x_scale_and_1080p_quality() {
        assertThat(StartupMode.High.densityValue).isEqualTo(2f)
        assertThat(StartupMode.High.quality).isEqualTo(Resolution.R1080P)
    }

    @Test
    fun modes_are_distinct_on_both_settings() {
        assertThat(StartupMode.Standard.densityValue).isNotEqualTo(StartupMode.High.densityValue)
        assertThat(StartupMode.Standard.quality).isNotEqualTo(StartupMode.High.quality)
    }

    @Test
    fun defaultMode_for_720p_screen_is_standard() {
        assertThat(defaultStartupModeFor(widthPixels = 1280, heightPixels = 720))
            .isEqualTo(StartupMode.Standard)
    }

    @Test
    fun defaultMode_for_1080p_screen_is_high() {
        assertThat(defaultStartupModeFor(widthPixels = 1920, heightPixels = 1080))
            .isEqualTo(StartupMode.High)
    }

    @Test
    fun defaultMode_for_4k_screen_is_high() {
        assertThat(defaultStartupModeFor(widthPixels = 3840, heightPixels = 2160))
            .isEqualTo(StartupMode.High)
    }

    @Test
    fun defaultMode_uses_long_side_so_portrait_phone_is_high() {
        // 手机竖屏 widthPixels 只有 1224，但长边 2712 属 1080P 级屏幕 ⇒ 高清。
        assertThat(defaultStartupModeFor(widthPixels = 1224, heightPixels = 2712))
            .isEqualTo(StartupMode.High)
    }
}
