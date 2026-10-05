package dev.frost819.newbv.app.ui.component.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.frost819.newbv.app.entity.player.VideoListItem
import dev.frost819.newbv.app.ui.component.videocard.VideoCardData
import dev.frost819.newbv.core.focus.focusInvertedColors
import dev.frost819.newbv.core.focus.touchClickable

/**
 * 卡片上留白：卡片顶部**紧贴顶部信息栏「N人正在看」那一行下面**。
 *
 * 该行在 800×500 实测文字底边 y≈74（1280×720 约 y≈73），行下方还有约 6px 呼吸位，
 * 故取 60dp（=60px @160dpi）而不是原先按信息栏自身 16dp 内边距算出的 88dp ——
 * 88dp 会在信息栏与卡片之间留下近 30px 的空档，浪费本就不高的屏。
 *
 * ⚠️ 视频标题为两行时该行会下移（约 y≈100~115），卡片会压住它；信息栏本身有底衬，
 * 压住只是观感问题，不遮挡操作。
 */
private val PAGE_PADDING_TOP = 60.dp

/**
 * 卡片下留白：卡片底部**尽量贴近进度时间显示**。
 *
 * 实测进度时间（"01:55 / 23:40"）文字顶边 y≈648，取 80dp ⇒ 卡片底边 y≈640，留 8px 不压字；
 * 下方按钮行（y≈683 起）完全不受影响。
 */
private val PAGE_PADDING_BOTTOM = 80.dp

/** 左右外边距：卡片与屏幕边缘留一点缝。 */
private val PAGE_HORIZONTAL_MARGIN = 12.dp

/**
 * 三栏宽度：左分集**按内容自适应**、中留白 1、右相关视频 2。
 *
 * 左栏不再用固定权重：分集标题长度差异极大（「第 1 集」到十几字的合集标题），
 * 固定权重时短标题会白占一片、长标题又读不全。改为量出**最长一条分集标题**的
 * 宽度再加内边距（见 [rememberExploreLeftWidth]），并夹在
 * [EXPLORE_LEFT_MIN_WIDTH] ~ [EXPLORE_LEFT_MAX_WIDTH] 之间。
 *
 * 上限按「占本栏可用宽度的比例」给（[EXPLORE_LEFT_MAX_WIDTH_FRACTION]）：合集/番剧
 * 的长标题需要到半屏宽才能读全，故放宽到 1/2；右栏宽度的保护交给权重分配
 * （右栏拿的是**剩下的**空间，左栏越宽右栏越窄，但永远不会被压到 0）。
 *
 * 中间留白保持 1 是为了让视频画面继续可见（留白的用途就是透出画面，
 * 不承载内容，因此不参与内容的宽度分配）。
 */
private val EXPLORE_LEFT_MIN_WIDTH = 148.dp

/** 左栏宽度上限（绝对 dp，防 4K 大屏下按比例算出过宽的卡片）。 */
private val EXPLORE_LEFT_MAX_WIDTH = 520.dp

/** 左栏宽度上限（占本栏可用宽度的比例）。 */
private const val EXPLORE_LEFT_MAX_WIDTH_FRACTION = 0.5f

/** 中间留白权重（透出视频画面，只留一条窄缝给画面）。 */
private const val EXPLORE_MIDDLE_WEIGHT = 0.6f

/** 右侧相关视频权重（内容更长，占剩余空间的大部分）。 */
private const val EXPLORE_RIGHT_WEIGHT = 2.6f

/** 卡片内边距。 */
private val CARD_INNER_PADDING = 12.dp

/** 列头到下文的间距。 */
private val HEADER_GAP = 8.dp

/**
 * 相关视频**双列**网格：标题在封面下方，两列塞进右栏。
 *
 * 版式选择：右栏只有 1/3 屏宽（720p 下约 628px、800×500 下约 388px），
 * 单列「160dp 封面 + 右侧两行标题」会把标题挤成半句；**竖版卡片（封面在上、
 * 标题在下）双列**能同时保住封面尺寸与标题行宽 —— 每列约 190px（800×500），
 * 标题按两行折行，比单列多显示约 4 成信息量，一屏还能看到 2 行 4 个。
 *
 * 封面仍是 16:9（[RELATED_COVER_ASPECT]），宽度由列宽决定，因此不再需要
 * 固定封面宽度常量。
 */
private val RELATED_GRID_SPACING = 8.dp

/** 相关视频列数：双列（右栏 1/3 屏宽下每列仍能放下 16:9 封面 + 两行标题）。 */
private const val RELATED_GRID_COLUMN_COUNT = 2

/** 双列网格的行距与内容内边距。 */
private val RELATED_ITEM_PADDING = 6.dp

/** 封面保持 16:9（宽度由列宽决定）。 */
private const val RELATED_COVER_ASPECT = 16f / 9f

/** 未聚焦时的行底色：与分集行同一口径的淡白条带。 */
private val RELATED_ITEM_IDLE_CONTAINER = Color.White.copy(alpha = 0.08f)

/**
 * 播放器「选集 / 相关视频」整合页。
 *
 * 版式：**两块实底卡片浮在视频之上**，横向各占屏幕 1/3 ——
 * 左 1/3 分集列表、中 1/3 整块留白（视频透出）、右 1/3 相关视频。
 * 不做整屏遮罩：卡片自身不透明即可保证可读性，中间留白让画面继续可见。
 *
 * 进入方式与焦点落点（见 [PlayerOverlayFocus]）：
 * - 方向上键 → 打开本页，焦点落在**左侧分集栏的当前集**；
 * - 底部控件「相关视频」按钮 → 打开本页，焦点落在**右侧相关栏第一条**。
 *
 * 与旧实现的关系：分集列表面板原先自成浮层、跟随底部控件显隐；现在它是本页左卡片，
 * 因此「进播放器自动弹列表」也随之取消（底部控件仍会亮一次，提示本页入口）。
 *
 * @param modifier 修饰符
 * @param show 是否显示
 * @param currentCid 当前播放的 CID，用于左侧高亮当前集
 * @param videoList 播放列表
 * @param relatedVideos 相关视频
 * @param focusTarget 本次唤出的焦点落点
 * @param onUserInteraction 页面内发生触摸（点击/滚动列表）时回调，用于重置自动关闭计时
 * @param onPlayNewVideo 选择分集回调
 * @param onVideoClicked 点击相关视频回调
 */
@Composable
fun PlayerExploreController(
    modifier: Modifier = Modifier,
    show: Boolean,
    currentCid: Long,
    videoList: List<VideoListItem>,
    relatedVideos: List<VideoCardData>,
    focusTarget: PlayerOverlayFocus = PlayerOverlayFocus.None,
    onUserInteraction: () -> Unit = {},
    onPlayNewVideo: (VideoListItem) -> Unit,
    onVideoClicked: (VideoCardData) -> Unit,
) {
    val firstRelatedRequester = remember { FocusRequester() }

    LaunchedEffect(show, focusTarget) {
        if (show && focusTarget == PlayerOverlayFocus.Related) {
            runCatching { firstRelatedRequester.requestFocus() }
        }
    }

    AnimatedVisibility(
        visible = show,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.fillMaxSize(),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val leftWidth =
                rememberExploreLeftWidth(
                    videoList = videoList,
                    maxWidth = minOf(EXPLORE_LEFT_MAX_WIDTH, maxWidth * EXPLORE_LEFT_MAX_WIDTH_FRACTION),
                    minWidth = EXPLORE_LEFT_MIN_WIDTH,
                )

            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        // 页面内任何触摸都算「有操作」，用于重置 5s 自动关闭计时；
                        // 不消费事件，所以行点击与列表滚动照常
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                onUserInteraction()
                            }
                        }.padding(
                            start = PAGE_HORIZONTAL_MARGIN,
                            end = PAGE_HORIZONTAL_MARGIN,
                            top = PAGE_PADDING_TOP,
                            bottom = PAGE_PADDING_BOTTOM,
                        ),
            ) {
                // 左：分集（宽度自适应，夹在 [EXPLORE_LEFT_MIN_WIDTH, 上限] 之间）
                ExploreCard(
                    modifier = Modifier.width(leftWidth).fillMaxHeight(),
                ) {
                    ExploreColumnHeader(text = "分集 · ${videoList.size}")
                    Spacer(Modifier.height(HEADER_GAP))
                    VideoListController(
                        modifier = Modifier.fillMaxSize(),
                        currentCid = currentCid,
                        videoList = videoList,
                        requestFocus = focusTarget == PlayerOverlayFocus.EpisodeList,
                        onPlayNewVideo = onPlayNewVideo,
                    )
                }

                // 中：整块留白，视频从这里透出来
                Spacer(Modifier.weight(EXPLORE_MIDDLE_WEIGHT))

                // 右：相关视频（双列竖版卡片，占剩余空间的两份）
                ExploreCard(
                    modifier = Modifier.weight(EXPLORE_RIGHT_WEIGHT).fillMaxHeight(),
                ) {
                    ExploreColumnHeader(text = "相关视频")
                    Spacer(Modifier.height(HEADER_GAP))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(RELATED_GRID_COLUMN_COUNT),
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(RELATED_GRID_SPACING),
                        verticalArrangement = Arrangement.spacedBy(RELATED_GRID_SPACING),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        itemsIndexed(
                            items = relatedVideos,
                            key = { _, video -> video.avid },
                        ) { index, video ->
                            RelatedVideoCard(
                                modifier =
                                    if (index == 0) {
                                        Modifier.focusRequester(firstRelatedRequester)
                                    } else {
                                        Modifier
                                    },
                                video = video,
                                onClick = { onVideoClicked(video) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 量出左栏（分集栏）需要的宽度：**最长一条分集标题**的文字宽度 + 卡片与行的水平内边距。
 *
 * 用 [rememberTextMeasurer] 在组合期量一次而不是让卡片 wrapContentWidth：
 * 分集栏内容是 [LazyColumn]，它**不支持 intrinsic 测量**（会直接抛异常），
 * 而 `fillMaxWidth` 的行在 wrap 约束下会被撑到上限，反而量不出内容宽度。
 *
 * 只量最长的一条（按字符数挑，CJK 与西文字宽不同，但作为上限估计足够），
 * 结果按 [maxWidth]/[minWidth] 夹紧。
 *
 * @param videoList 播放列表
 * @param maxWidth 本栏可用宽度上限
 * @param minWidth 本栏宽度下限
 */
@Composable
private fun rememberExploreLeftWidth(
    videoList: List<VideoListItem>,
    maxWidth: Dp,
    minWidth: Dp,
): Dp {
    val textMeasurer = rememberTextMeasurer()
    val textStyle = MaterialTheme.typography.titleLarge
    val density = LocalDensity.current
    return remember(videoList, textStyle, maxWidth, minWidth, density) {
        // 卡片内边距 + 行内文字水平内边距，与 PlayerListItem 的排版口径一致
        val padding = CARD_INNER_PADDING * 2 + ITEM_HORIZONTAL_PADDING * 2
        val longest = videoList.episodeTitles().maxByOrNull { it.length }.orEmpty()
        val textWidth =
            if (longest.isEmpty()) {
                0.dp
            } else {
                with(density) {
                    textMeasurer
                        .measure(
                            text = longest,
                            style = textStyle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        ).size.width
                        .toDp()
                }
            }
        (textWidth + padding).coerceIn(minWidth, maxWidth)
    }
}

/**
 * 实底卡片：不透明底 + 圆角，浮在视频之上。
 *
 * 之所以要求实底（而不是半透明遮罩），是因为弹幕层在 `content()` 里、位于本页之下，
 * 半透明时亮白弹幕会透上来糊住文字；整屏遮罩又会挡住画面，故改为两块卡片。
 *
 * @param modifier 修饰符（宽高由调用方给定）
 * @param content 卡片内容（已提供 [ColumnScope]）
 */
@Composable
private fun ExploreCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(CARD_INNER_PADDING),
            content = content,
        )
    }
}

/**
 * 列头文字。
 *
 * @param text 列头文本
 */
@Composable
private fun ExploreColumnHeader(text: String) {
    Text(
        modifier = Modifier.padding(horizontal = 4.dp),
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 相关视频卡片（竖版）：上封面（16:9，右下角时长）+ 下文字（标题两行 + UP 主/播放/弹幕）。
 *
 * 竖版是为了在右栏宽度里排**双列**：横版「160dp 封面 + 右侧文字」单列 628px（720p）
 * 只够放半句标题；改成竖版后每列约 190~300px，封面按列宽 16:9 自适应，
 * 标题在封面下方折行两行，同宽下信息量约为单列横版的 1.6 倍
 * （双列 × 两行标题），且一屏可见 2 行 4 个。
 *
 * @param modifier 修饰符
 * @param video 视频数据
 * @param onClick 点击回调
 */
@Composable
private fun RelatedVideoCard(
    modifier: Modifier = Modifier,
    video: VideoCardData,
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .touchClickable(onClick = onClick),
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.small),
        // 与分集行同一口径：不要 tv-material3 默认的 1.1× 焦点放大，避免标题被推出行外
        scale =
            ClickableSurfaceDefaults.scale(
                focusedScale = 1f,
                pressedScale = 1f,
            ),
        colors =
            focusInvertedColors(
                containerColor = RELATED_ITEM_IDLE_CONTAINER,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(RELATED_ITEM_PADDING)) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(RELATED_COVER_ASPECT)
                        .clip(MaterialTheme.shapes.small),
            ) {
                AsyncImage(
                    modifier = Modifier.fillMaxSize(),
                    model = video.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                )
                if (video.timeString.isNotBlank()) {
                    Text(
                        modifier =
                            Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .clip(MaterialTheme.shapes.extraSmall)
                                .background(Color.Black.copy(alpha = 0.65f))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        text = video.timeString,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                    )
                }
            }

            Spacer(Modifier.height(RELATED_ITEM_PADDING))

            Text(
                text = video.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            val stats =
                listOf(video.upName, video.playString, video.danmakuString)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
            if (stats.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stats,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// region Previews

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PlayerExploreControllerPreview() {
    val episodes =
        listOf(
            VideoListItem(aid = 1, cid = 101, title = "第 1 集 · 序幕"),
            VideoListItem(aid = 1, cid = 102, title = "第 2 集 · 出发"),
            VideoListItem(aid = 1, cid = 103, title = "第 3 集 · 迷雾"),
        )
    val related =
        listOf(
            VideoCardData(
                avid = 1,
                cid = 101,
                title = "相关视频标题一，可能会很长很长很长很长很长很长",
                cover = "",
                upName = "UP主A",
                playString = "12.3万",
                danmakuString = "300",
                timeString = "10:30",
            ),
            VideoCardData(
                avid = 2,
                cid = 102,
                title = "相关视频标题二",
                cover = "",
                upName = "UP主B",
                playString = "5000",
                danmakuString = "100",
                timeString = "05:20",
            ),
        )

    dev.frost819.newbv.core.theme.BVTheme(themeMode = dev.frost819.newbv.core.theme.ThemeMode.Dark) {
        PlayerExploreController(
            show = true,
            currentCid = 102,
            videoList = episodes,
            relatedVideos = related,
            focusTarget = PlayerOverlayFocus.EpisodeList,
            onPlayNewVideo = {},
            onVideoClicked = {},
        )
    }
}

// endregion
