package dev.frost819.newbv.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import dev.frost819.newbv.app.ui.screen.detail.videoDetailScreen
import dev.frost819.newbv.app.ui.screen.home.homeScreen
import dev.frost819.newbv.app.ui.screen.live.liveAreaScreen
import dev.frost819.newbv.app.ui.screen.live.liveFollowScreen
import dev.frost819.newbv.app.ui.screen.login.loginScreen
import dev.frost819.newbv.app.ui.screen.onboarding.onboardingScreen
import dev.frost819.newbv.app.ui.screen.pgc.pgcFeatureScreen
import dev.frost819.newbv.app.ui.screen.player.livePlayerScreen
import dev.frost819.newbv.app.ui.screen.player.seasonPlayerScreen
import dev.frost819.newbv.app.ui.screen.player.videoPlayerScreen
import dev.frost819.newbv.app.ui.screen.search.searchResultScreen
import dev.frost819.newbv.app.ui.screen.settings.settingsScreen
import dev.frost819.newbv.app.ui.screen.user.followScreen
import dev.frost819.newbv.app.ui.screen.user.userSpaceScreen
import dev.frost819.newbv.app.ui.screen.user.userSwitchScreen
import dev.frost819.newbv.core.log.Loggers
import dev.frost819.newbv.data.datastore.Prefs

/**
 * 应用 Navigation 宿主。
 *
 * 单 Activity 架构：所有页面通过 Navigation-Compose 导航，无新 Activity 启动。
 * 路由定义见 [Routes]。
 *
 * 起始目的地按 [Prefs.isOnboarded] 决定：**未完成首次启动引导时进模式选择页**，
 * 否则直接进主页。读的是 [Prefs] 的内存缓存（`Application.onCreate` 已阻塞加载完毕），
 * 不涉及 IO，也不会在重组时反复求值 —— [AppNavHost] 只在应用启动时组合一次。
 *
 * @param navController 导航控制器，默认使用 [rememberNavController]
 * @param startDestination 起始路由，默认按引导完成状态推导
 */
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: Any = if (Prefs.isOnboarded) HomeRoute else OnboardingRoute,
) {
    val logger = Loggers.get("AppNavHost")

    LaunchedEffect(navController) {
        var previousRoute: String? = null
        navController.currentBackStackEntryFlow.collect { entry ->
            val route = entry.destination.route?.substringAfterLast('.') ?: "unknown"
            if (previousRoute != null && previousRoute != route) {
                logger.info { "[NAV] from=$previousRoute to=$route" }
            }
            previousRoute = route
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        // ── 首次启动 ─────────────────────────────────────────────────
        onboardingScreen(navController)

        // ── 主流程 ────────────────────────────────────────────────────
        homeScreen(navController)
        searchResultScreen(navController)

        // ── 视频详情 ─────────────────────────────────────────────────
        videoDetailScreen(navController)

        // ── 直播 ─────────────────────────────────────────────────────
        liveAreaScreen(navController)
        liveFollowScreen(navController)

        // ── 播放器 ───────────────────────────────────────────────────
        videoPlayerScreen(navController)
        seasonPlayerScreen(navController)
        livePlayerScreen(navController)

        // ── 用户 ─────────────────────────────────────────────────────
        userSpaceScreen(navController)
        followScreen(navController)

        // ── PGC ──────────────────────────────────────────────────────
        pgcFeatureScreen(navController)

        // ── 设置 ─────────────────────────────────────────────────────
        settingsScreen(navController)
        userSwitchScreen(navController)

        // ── 登录 ─────────────────────────────────────────────────────
        loginScreen(navController)
    }
}
