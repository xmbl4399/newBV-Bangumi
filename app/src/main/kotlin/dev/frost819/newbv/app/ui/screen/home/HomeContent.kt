package dev.frost819.newbv.app.ui.screen.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import dev.frost819.newbv.app.ui.component.FocusSaver
import dev.frost819.newbv.app.ui.component.HomeTabItem
import dev.frost819.newbv.app.ui.component.TopNav
import dev.frost819.newbv.app.ui.component.bangumiCategory
import dev.frost819.newbv.app.ui.screen.bangumi.BangumiContent
import dev.frost819.newbv.app.viewmodel.home.HomeViewModel
import dev.frost819.newbv.data.datastore.HomeTopNavItem
import dev.frost819.newbv.data.datastore.Prefs
import androidx.compose.material3.Scaffold as Material3Scaffold

/**
 * 首页内容（TopNav + 各分类子页）。
 *
 * 顶部 Tab **顺序固定**为「动态 → 推荐 → 热门 → TV动画 → 其他动画 → 日剧 →
 * 欧美剧 → 华语剧 → 韩剧 → 电影」，不随「默认首页 Tab」设置重排 ——
 * 该设置只决定**首次进入时选中哪一项**，避免用户改一次默认页就把整条分类栏打乱。
 * Tab 切换使用 [AnimatedContent] 横向滑动，菜单键刷新当前 Tab 数据。
 *
 * 后 7 项为 Bangumi 分类（文案与顺序照搬 blbl-Bangumi），
 * 内容由 [BangumiContent] 承载，各自持有独立的 ViewModel 实例。
 *
 * @param navFocusRequester 顶部 Tab 的焦点请求器（由 MainScreen 传入）。
 * @param navController 导航控制器（跳转详情页等）。
 * @param focusSaver 焦点恢复器（由 MainScreen 共享传入）。
 */
@Composable
fun HomeContent(
    navFocusRequester: FocusRequester,
    navController: NavController,
    focusSaver: FocusSaver,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val items = remember { HomeTopNavItem.entries.map { HomeTabItem(it) } }
    val firstTab = remember { Prefs.firstHomeTopNavItem }
    var selectedTab by rememberSaveable { mutableStateOf(firstTab) }
    var focusOnContent by remember { mutableStateOf(false) }
    val uiState by viewModel.uiState.collectAsState()

    // Tab 选中的唯一入口：D-Pad 聚焦（onFocus）与手指点击（onClick）都走这里。
    // TabRow 的 onFocus 只在获得焦点时触发，触摸点击若不复用这条路径，
    // 就会出现「手指点上去没反应、只有遥控器能切」。
    val selectTab: (HomeTopNavItem) -> Unit = { tab ->
        selectedTab = tab
        if (tab == HomeTopNavItem.Dynamics && uiState.dynamicItems.isEmpty()) {
            viewModel.loadDynamic()
        }
    }

    // 在内容区（首页视频卡片 / Bangumi 封面卡片 / 年份栏）里按返回键：焦点回到顶部
    // Tab，而不是直接进入 MainScreen 的「再按一次退出」倒数。
    // 焦点已在顶部 Tab 或左侧栏时 focusOnContent 为 false，本处理器不拦截，
    // 由 MainScreen 的返回逻辑（倒数退出）接管 —— 于是形成
    // 「卡片 → 顶部 Tab → 再按退出」的两级返回。
    BackHandler(enabled = focusOnContent) {
        runCatching { navFocusRequester.requestFocus() }
    }

    Material3Scaffold(
        topBar = {
            TopNav(
                modifier = Modifier.focusRequester(navFocusRequester),
                items = items,
                selectedIndex = items.indexOf(HomeTabItem(selectedTab)),
                isLargePadding = !focusOnContent,
                onSelectedChanged = { nav ->
                    selectTab((nav as HomeTabItem).item)
                },
                onClick = { nav ->
                    val tab = (nav as HomeTabItem).item
                    selectTab(tab)
                    // 点击同一 Tab 时刷新；Bangumi 分类 Tab 在 HomeViewModel 里是 no-op
                    viewModel.refresh(tab)
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .onFocusChanged { focusOnContent = it.hasFocus }
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Menu && event.type == KeyEventType.KeyUp) {
                            viewModel.refresh(selectedTab)
                            navFocusRequester.requestFocus()
                            return@onPreviewKeyEvent true
                        }
                        false
                    },
        ) {
            AnimatedContent(
                targetState = selectedTab,
                label = "home-animated-content",
                transitionSpec = {
                    val coefficient = 10
                    if (items.indexOf(HomeTabItem(targetState)) <
                        items.indexOf(HomeTabItem(initialState))
                    ) {
                        fadeIn() + slideInHorizontally { -it / coefficient } togetherWith
                            fadeOut() + slideOutHorizontally { it / coefficient }
                    } else {
                        fadeIn() + slideInHorizontally { it / coefficient } togetherWith
                            fadeOut() + slideOutHorizontally { -it / coefficient }
                    }
                },
            ) { screen ->
                when (screen) {
                    HomeTopNavItem.Recommend ->
                        RecommendScreen(
                            viewModel = viewModel,
                            navController = navController,
                            focusSaver = focusSaver,
                        )
                    HomeTopNavItem.Popular ->
                        PopularScreen(
                            viewModel = viewModel,
                            navController = navController,
                            focusSaver = focusSaver,
                        )
                    HomeTopNavItem.Dynamics ->
                        DynamicsScreen(
                            viewModel = viewModel,
                            navController = navController,
                            focusSaver = focusSaver,
                        )
                    HomeTopNavItem.TvAnime,
                    HomeTopNavItem.AnimeMovie,
                    HomeTopNavItem.JpDrama,
                    HomeTopNavItem.WesternDrama,
                    HomeTopNavItem.ChineseDrama,
                    HomeTopNavItem.KoreanDrama,
                    HomeTopNavItem.Movie,
                    -> {
                        screen.bangumiCategory?.let { category ->
                            BangumiContent(
                                category = category,
                                navController = navController,
                                focusSaver = focusSaver,
                            )
                        }
                    }
                }
            }
        }
    }
}
