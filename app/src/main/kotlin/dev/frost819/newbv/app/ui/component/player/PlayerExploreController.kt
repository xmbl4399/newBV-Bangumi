package dev.frost819.newbv.app.ui.component.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
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
 * 卡片上留白：卡片顶部**顶到顶部信息栏「N人正在看」那一行下面**。
 *
 * 实测（1280×720）该行文字底边 y≈73，信息栏自身另有 16dp 下内边距 ⇒ 88dp 正好贴在其下方。
 * ⚠️ 标题为两行时这一行会下移（约 y≈100~115），卡片会压住它；要保险把本值调到 120dp。
 */
private val PAGE_PADDING_TOP = 88.dp

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
 * 三栏宽度权重：左分集 1、中留白 1、右相关视频 2。
 *
 * 不做平均三栏是因为**两侧内容对宽度的需求差一倍**：分集标题 6~8 字、
 * 相关视频标题 20 字以上。平均分配会让相关视频标题只剩 209px（720p）
 * 而被截断成读不完的半句，分集栏却有大量空白浪费。
 *
 * 中间留白保持 1 是为了让视频画面继续可见（留白的用途就是透出画面，
 * 不承载内容，因此不参与内容的宽度分配）。
 */
private const val EXPLORE_LEFT_WEIGHT = 1f

/** 中间留白权重（透出视频画面）。 */
private const val EXPLORE_MIDDLE_WEIGHT = 1f

/** 右侧相关视频权重（内容更长，占两份）。 */
private const val EXPLORE_RIGHT_WEIGHT = 2f

/** 卡片内边距。 */
private val CARD_INNER_PADDING = 12.dp

/** 列头到下文的间距。 */
private val HEADER_GAP = 8.dp

/**
 * 相关视频行封面宽（16:9）。
 *
 * 保持 160dp 不缩水：加宽右栏后标题已有充足宽度，封面无需再让位。
 */
private val RELATED_COVER_WIDTH = 160.dp

/** 相关视频行的行距与内边距。 */
private val RELATED_ITEM_SPACING = 8.dp
private val RELATED_ITEM_PADDING = 8.dp

/** 封面与文字之间的水平间隔。 */
private val RELATED_COVER_GAP = 10.dp

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
                    }
                    .padding(
                        start = PAGE_HORIZONTAL_MARGIN,
                        end = PAGE_HORIZONTAL_MARGIN,
                        top = PAGE_PADDING_TOP,
                        bottom = PAGE_PADDING_BOTTOM,
                    ),
        ) {
            // 左 1/4：分集（标题短，不需要宽）
            ExploreCard(
                modifier = Modifier.weight(EXPLORE_LEFT_WEIGHT).fillMaxHeight(),
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

            // 中 1/4：整块留白，视频从这里透出来
            Spacer(Modifier.weight(EXPLORE_MIDDLE_WEIGHT))

            // 右 1/2：相关视频（标题长，占两份宽度）
            ExploreCard(
                modifier = Modifier.weight(EXPLORE_RIGHT_WEIGHT).fillMaxHeight(),
            ) {
                ExploreColumnHeader(text = "相关视频")
                Spacer(Modifier.height(HEADER_GAP))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(RELATED_ITEM_SPACING),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    itemsIndexed(
                        items = relatedVideos,
                        key = { _, video -> video.avid },
                    ) { index, video ->
                        RelatedVideoRow(
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
 * 相关视频行：左封面（16:9，右下角时长）+ 右文字（标题两行 + UP 主 + 播放/弹幕数）。
 *
 * 宽度分配是**按内容需求**定的，不是平均三栏：
 * - 分集标题短（「奥日与黑暗森林」这类 6~8 字），1/4 栏足够；
 * - 相关视频标题长（普遍 20 字以上），需要更宽。
 *
 * 因此左栏 1/4、右栏 1/2（见 [EXPLORE_LEFT_WEIGHT] / [EXPLORE_RIGHT_WEIGHT]）。
 * 实测 720p 下右栏 628px，封面 160dp 不变时标题可用 418px ≈ 26 字/行、两行 52 字，
 * 相比平均三栏的 209px（26 字/两行）翻倍，标题基本可完整显示，
 * 而封面尺寸与一屏可见条数都没有牺牲。
 *
 * @param modifier 修饰符
 * @param video 视频数据
 * @param onClick 点击回调
 */
@Composable
private fun RelatedVideoRow(
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(RELATED_ITEM_PADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .width(RELATED_COVER_WIDTH)
                        .aspectRatio(16f / 9f)
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

            Spacer(Modifier.width(RELATED_COVER_GAP))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                val stats =
                    listOf(video.upName, video.playString, video.danmakuString)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
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
