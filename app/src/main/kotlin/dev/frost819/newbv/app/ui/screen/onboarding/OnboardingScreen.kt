package dev.frost819.newbv.app.ui.screen.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.frost819.newbv.app.ui.navigation.HomeRoute
import dev.frost819.newbv.app.ui.navigation.OnboardingRoute
import dev.frost819.newbv.core.focus.touchClickable

/**
 * 首次启动模式选择页导航注册。
 *
 * 无参数、无返回栈前驱（它是起始目的地），选择完成后 `popUpTo` 自身并落到主页，
 * 保证返回键无法退回本页。
 *
 * @param navController 导航控制器。
 */
fun NavGraphBuilder.onboardingScreen(navController: NavController) {
    composable<OnboardingRoute> {
        OnboardingContent(
            onConfirm = { mode ->
                applyStartupMode(mode)
                navController.navigate(HomeRoute) {
                    // 引导页是一次性的：从回退栈里摘掉，主页上按返回键不会回到这里。
                    popUpTo<OnboardingRoute> { inclusive = true }
                }
            },
        )
    }
}

/**
 * 首次启动的「标清 / 高清」模式选择页。
 *
 * 交互契约（产品要求：触摸与遥控器方向键都要能用）：
 * - **遥控器**：左右方向键在两档之间搬焦点，中心/回车键确认并进入主页；
 * - **触摸**：点任一卡片即选中并确认（`touchClickable` 同时负责请求焦点，
 *   让「点哪张哪张高亮」与遥控器高亮样式保持一致）；
 * - 两档卡片会自动请求焦点，冷启动时用户直接按方向键/确认键即可，无需先按一下别的键。
 *
 * @param onConfirm 用户确定模式后的回调。
 */
@Composable
fun OnboardingContent(
    modifier: Modifier = Modifier,
    onConfirm: (StartupMode) -> Unit = {},
) {
    val configuration = LocalConfiguration.current
    val suggested =
        remember(configuration.screenWidthDp, configuration.screenHeightDp) {
            defaultStartupModeFor(
                widthPixels = configuration.screenWidthDp,
                heightPixels = configuration.screenHeightDp,
            )
        }

    var selected by remember { mutableStateOf(suggested) }
    val standardFocus = remember { FocusRequester() }
    val highFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // 冷启动把焦点落在默认档上，用户直接按方向键/确认键即可，不用先按一下别的键。
        val target = if (suggested == StartupMode.Standard) standardFocus else highFocus
        runCatching { target.requestFocus() }
    }

    /**
     * 把方向键收敛成「两档之间横跳 + 确认键确定」。
     *
     * 处理器**挂在每张卡片自己的焦点节点上**（见 [StartupModeCard] 的 `onKeyHandler`），
     * 不是挂在页根：此前挂在页根的 `onPreviewKeyEvent` 收不到事件 ——
     * 卡片是更近的祖先，方向键在那里就被消费了，页根预览根本不会跑，
     * 于是按左右键毫无反应（实测确认：焦点一直停在标清卡）。
     *
     * 左右键**同时搬真实焦点与选中态**：只改 `selected` 不搬焦点，会留下
     * 「焦点仍在标清、高亮却在高清」的分裂态（实测复现过）。两者一起走，
     * 页内始终只有一个档位是「亮着的」。
     */
    val onCardKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else {
            when (event.key) {
                Key.DirectionLeft -> {
                    selected = StartupMode.Standard
                    runCatching { standardFocus.requestFocus() }
                    true
                }

                Key.DirectionRight -> {
                    selected = StartupMode.High
                    runCatching { highFocus.requestFocus() }
                    true
                }

                // 上下键在本页无意义，吃掉以免焦点被默认一维搜索带出屏幕边缘。
                Key.DirectionUp, Key.DirectionDown -> true

                Key.DirectionCenter, Key.Enter -> {
                    onConfirm(selected)
                    true
                }

                else -> false
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    MaterialTheme.colorScheme.background,
                                    MaterialTheme.colorScheme.surface,
                                ),
                        ),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 720.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Text(
                    text = "选择播放模式",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "决定界面缩放与默认画质，之后可在设置里单独调整",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    StartupModeCard(
                        modifier = Modifier.weight(1f),
                        cardFocus = standardFocus,
                        mode = StartupMode.Standard,
                        title = "标清模式",
                        scaleText = "界面缩放 1x",
                        qualityText = "默认画质 720P",
                        selected = selected == StartupMode.Standard,
                        onSelected = { selected = StartupMode.Standard },
                        onConfirm = { onConfirm(StartupMode.Standard) },
                        onKeyHandler = onCardKey,
                    )
                    StartupModeCard(
                        modifier = Modifier.weight(1f),
                        cardFocus = highFocus,
                        mode = StartupMode.High,
                        title = "高清模式",
                        scaleText = "界面缩放 2x",
                        qualityText = "默认画质 1080P",
                        selected = selected == StartupMode.High,
                        onSelected = { selected = StartupMode.High },
                        onConfirm = { onConfirm(StartupMode.High) },
                        onKeyHandler = onCardKey,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "左右方向键切换，按确认键开始",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 单个模式卡片。
 *
 * **外观只认页级 [selected]，焦点不参与选中判定**：曾经在 `onFocusChanged` 里
 * 反向写 `selected`，结果「对焦到哪张卡」和「哪张卡是高亮档」互相覆盖，
 * 出现焦点已到高清、高亮底色却留在标清的打架态。现在 [hasFocus] 只驱动
 * 标题下那条细线，选中态的唯一数据源是 [selected]。
 *
 * @param mode 本卡对应的模式（用于触摸回调区分）。
 * @param cardFocus 本卡的焦点请求器（供 [OnboardingContent] 搬运焦点用）。
 * @param title 卡片主标题。
 * @param scaleText 界面缩放说明行。
 * @param qualityText 默认画质说明行。
 * @param selected 是否为当前选中项（页级唯一数据源）。
 * @param onSelected 触摸点击时回调，用于切换页级选中态。
 * @param onConfirm 点击确认时回调。
 * @param onKeyHandler 方向键/确认键处理器（两卡共用，见 [OnboardingContent] 的 `onCardKey`）。
 */
@Composable
private fun StartupModeCard(
    modifier: Modifier = Modifier,
    cardFocus: FocusRequester,
    mode: StartupMode,
    title: String,
    scaleText: String,
    qualityText: String,
    selected: Boolean,
    onSelected: (StartupMode) -> Unit,
    onConfirm: (StartupMode) -> Unit,
    onKeyHandler: (androidx.compose.ui.input.key.KeyEvent) -> Boolean,
) {
    var hasFocus by remember { mutableStateOf(false) }

    Surface(
        // 不用 Surface 的 onClick：`tvClickable` 会在卡片内部再建一个焦点节点并自己
        // 消费方向键，导致外层挂的键处理收不到事件（实测按左右键无反应）。
        // 这里自己用 `focusable()` 建唯一焦点节点 + `onPreviewKeyEvent` 接管按键，
        // 触摸则走 `touchClickable`，两条输入路径都落在同一个节点上，行为可控。
        modifier =
            modifier
                .clip(RoundedCornerShape(16.dp))
                .onFocusChanged { hasFocus = it.hasFocus }
                .focusRequester(cardFocus)
                .focusable()
                .onPreviewKeyEvent(onKeyHandler)
                // 触摸点击一次到位：先切到本档再确认，与遥控器「左右选 + 确认键」等价。
                // `touchClickable` 内部会先请求焦点，因此触摸后焦点与选中态也会对齐。
                .touchClickable(
                    onClick = {
                        onSelected(mode)
                        onConfirm(mode)
                    },
                ),
        shape = RoundedCornerShape(16.dp),
        colors =
            androidx.tv.material3.SurfaceDefaults.colors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                contentColor =
                    if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            ),
        border =
            Border(
                // 边框同时表达两件事，避免多套焦点/选中样式互相打架：
                // - 宽度：选中或聚焦时为 3dp，否则 1dp（一眼看出方向键作用在哪张卡）；
                // - 颜色：选中用 secondary（绿），仅聚焦用 border 色。
                border =
                    BorderStroke(
                        width = if (selected || hasFocus) 3.dp else 1.dp,
                        color =
                            when {
                                selected -> MaterialTheme.colorScheme.secondary
                                hasFocus -> MaterialTheme.colorScheme.border
                                else -> MaterialTheme.colorScheme.border
                            },
                    ),
                shape = RoundedCornerShape(16.dp),
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(
                            // 细线只标示「方向键现在作用在哪张卡」，不参与选中态。
                            if (hasFocus) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                Color.Transparent
                            },
                        ),
            )
            Text(text = scaleText, style = MaterialTheme.typography.bodyLarge)
            Text(text = qualityText, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
