package dev.frost819.newbv.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.frost819.newbv.app.ui.navigation.AppNavHost
import dev.frost819.newbv.app.ui.navigation.HomeRoute
import dev.frost819.newbv.core.interaction.InteractionTracker
import dev.frost819.newbv.core.interaction.LocalInteractionTracker
import dev.frost819.newbv.core.log.Loggers
import dev.frost819.newbv.core.theme.BVTheme
import dev.frost819.newbv.core.theme.SystemBarsEffect
import dev.frost819.newbv.core.theme.ThemeMode
import dev.frost819.newbv.data.datastore.Prefs
import javax.inject.Inject

/**
 * 主 Activity（单 Activity 架构）。
 *
 * 职责：
 * - SplashScreen 显示
 * - 交互模式追踪（触屏/遥控器）
 * - Navigation 宿主（[AppNavHost]）
 * - 主题模式 + density 从 Prefs 实时读取
 * - edge-to-edge 与系统栏安全区避让（手机横屏时状态栏不得压住首页 Tab）
 *
 * 所有页面通过 Navigation-Compose 导航，不启动新 Activity。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val logger = Loggers.get("MainActivity")

    @Inject
    lateinit var interactionTracker: InteractionTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // 显式开启 edge-to-edge：Android 15（targetSdk 35）已强制此项，旧版本上保持行为一致。
        // 内容不再被系统自动避让，改为在 Compose 里按 WindowInsets 主动避让
        // （见下方 windowInsetsPadding）——否则手机横屏时状态栏会压在首页顶部分类栏上，
        // 那一排 Tab 既看不清也点不到。TV 上系统栏 insets 为 0，等于没有这层 padding。
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val themeMode by Prefs.themeModeFlow.collectAsState(initial = ThemeMode.Dark)
            val density by Prefs.densityFlow.collectAsState(initial = 2.0f)

            val coreThemeMode =
                when (themeMode) {
                    dev.frost819.newbv.data.datastore.ThemeMode.FollowSystem -> ThemeMode.FollowSystem
                    dev.frost819.newbv.data.datastore.ThemeMode.Dark -> ThemeMode.Dark
                    dev.frost819.newbv.data.datastore.ThemeMode.Light -> ThemeMode.Light
                    else -> ThemeMode.Dark
                }

            BVTheme(themeMode = coreThemeMode, density = density) {
                SystemBarsEffect()
                CompositionLocalProvider(
                    LocalInteractionTracker provides interactionTracker,
                ) {
                    // Surface 负责把主题底色铺满整块屏幕（含系统栏区域），
                    // 内层 Box 再按安全区缩进，保证「背景满屏、内容不被遮挡」。
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .windowInsetsPadding(WindowInsets.safeDrawing),
                        ) {
                            AppNavHost(startDestination = HomeRoute)
                        }
                    }
                }
            }
        }
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        interactionTracker.onTouch()
        return super.onTouchEvent(event)
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            interactionTracker.onDpadKey()
            logger.info { "[INPUT] keyDown keyCode=${KeyEvent.keyCodeToString(event.keyCode)}" }
        }
        return super.dispatchKeyEvent(event)
    }
}
