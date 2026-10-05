package dev.frost819.newbv.app.ui.component.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.frost819.newbv.app.entity.player.VideoListItem
import dev.frost819.newbv.core.focus.focusInvertedColors
import dev.frost819.newbv.core.focus.touchClickable

/** 行内文字的水平内边距（整合页测量左栏宽度时复用，故为 internal）。 */
internal val ITEM_HORIZONTAL_PADDING = 16.dp

/** 非当前集、未聚焦时的行底色（淡白条带）。 */
private val ITEM_IDLE_CONTAINER = Color.White.copy(alpha = 0.08f)

/**
 * 分集列表（「选集 / 相关视频」整合页的左卡片内容）。
 *
 * 只负责一列内容：底色由外层卡片提供，这里只铺 LazyColumn。多分 P 已摊平成
 * 「一行一集」交给 LazyColumn 虚拟化（200 集时不会把整份列表一次性组合）。
 *
 * 显隐、位置与进出场动画由调用方（整合页）负责 —— 面板原先跟随底部控件显隐、自成浮层，
 * 现在改为整合页左卡片的内容，因此这里不再有 AnimatedVisibility 与自己的底色。
 *
 * @param modifier 修饰符（尺寸由卡片给定）
 * @param currentCid 当前播放分集的 CID，用于高亮与定位
 * @param videoList 播放列表（含多分 P 的合集/分 P 列表）
 * @param requestFocus 本次唤出是否把焦点落到当前集上（右侧相关栏唤出时不抢焦点）
 * @param onPlayNewVideo 选择其它分集回调
 */
@Composable
fun VideoListController(
    modifier: Modifier = Modifier,
    currentCid: Long,
    videoList: List<VideoListItem>,
    requestFocus: Boolean = false,
    onPlayNewVideo: (VideoListItem) -> Unit,
) {
    val listState = rememberLazyListState()
    val currentRowFocusRequester = remember { FocusRequester() }

    val rows = remember(videoList) { videoList.toEpisodeRows() }
    val currentRowIndex = remember(rows, currentCid) { rows.indexOfFirst { it.cid == currentCid } }

    // 显示时定位到当前集；是否抢焦点由唤出方式决定（见 PlayerOverlayFocus）。
    LaunchedEffect(requestFocus, currentRowIndex) {
        if (currentRowIndex < 0) return@LaunchedEffect
        // 瞬时定位而非 animateScrollToItem：超多集时长距离动画要连续组合大量行，又慢又晃
        listState.scrollToItem(currentRowIndex)
        if (requestFocus) {
            runCatching { currentRowFocusRequester.requestFocus() }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(
            items = rows,
            key = { it.index },
        ) { row ->
            val selected = row.cid == currentCid
            PlayerListItem(
                modifier =
                    if (selected) {
                        Modifier.focusRequester(currentRowFocusRequester)
                    } else {
                        Modifier
                    },
                text = row.title,
                selected = selected,
                textAlign = TextAlign.Start,
                onClick = {
                    if (!selected) onPlayNewVideo(row.video.copy(cid = row.cid))
                },
            )
        }
    }
}

/**
 * 分集行：LazyColumn 的最小单元，一行 = 一个可播放条目。
 *
 * @property index 摊平后的下标，直接用作 LazyColumn 的 key
 *   （分 P 的 cid 理论上唯一，但 cid 未解析完时可能是 0，用下标可避免重复 key 崩溃）
 * @property video 所属视频项；点击时回传，内部会把 cid 换成该行的分 P
 * @property cid 该行 CID
 * @property title 行标题
 */
private data class EpisodeRow(
    val index: Int,
    val video: VideoListItem,
    val cid: Long,
    val title: String,
)

/**
 * 把播放列表摊平成逐行分集：多分 P 铺开成多行，单集视频自身一行。
 *
 * 摊平是为了让 LazyColumn 能真正虚拟化 —— 若把分 P 留在同一项里用 Column 铺开，
 * 再多的集数也只有一个 lazy item，整份列表会在一次组合里全部构建出来。
 */
private fun List<VideoListItem>.toEpisodeRows(): List<EpisodeRow> =
    toEpisodeEntries().mapIndexed { index, entry ->
        EpisodeRow(index = index, video = entry.video, cid = entry.cid, title = entry.title)
    }

/**
 * 分集标题（多分 P 摊平后逐行的顺序），供整合页按内容测量左栏宽度。
 *
 * 与 [toEpisodeRows] 共用 [toEpisodeEntries]，保证「一行显示哪个标题」的口径唯一，
 * 不会出现「量到的标题」与「实际渲染的标题」不一致。
 */
internal fun List<VideoListItem>.episodeTitles(): List<String> = toEpisodeEntries().map { it.title }

/**
 * 摊平后的单条分集（尚未编号）。
 *
 * @property video 所属视频项；点击时回传，内部会把 cid 换成该行的分 P
 * @property cid 该行 CID
 * @property title 行标题
 */
private data class EpisodeEntry(
    val video: VideoListItem,
    val cid: Long,
    val title: String,
)

private fun List<VideoListItem>.toEpisodeEntries(): List<EpisodeEntry> =
    buildList {
        this@toEpisodeEntries.forEach { item ->
            val pages = item.ugcPages.orEmpty()
            if (pages.isEmpty()) {
                add(EpisodeEntry(video = item, cid = item.cid, title = item.title))
            } else {
                pages.forEach { page ->
                    add(EpisodeEntry(video = item, cid = page.cid, title = page.title))
                }
            }
        }
    }

/**
 * 播放器列表项。
 *
 * 焦点态用工程统一的反色（浅底深字）+ 主色实底标出当前播放集。
 *
 * @param modifier 修饰符
 * @param text 文本
 * @param selected 是否选中
 * @param textAlign 文本对齐方式
 * @param onFocus 获得焦点回调
 * @param onClick 点击回调
 */
@Composable
fun PlayerListItem(
    modifier: Modifier = Modifier,
    text: String,
    selected: Boolean,
    textAlign: TextAlign = TextAlign.Center,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .onFocusChanged { if (it.hasFocus) onFocus() }
                .touchClickable(onClick = onClick),
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.small),
        // 关掉 tv-material3 的焦点放大：ClickableSurfaceDefaults.scale() 默认
        // focusedScale = 1.1f，长合集标题本来就几乎占满整栏，放大 10% 会顶出卡片与屏幕，
        // 方向键切集时文字还会忽大忽小。焦点继续靠下面的反色（浅底深字）标识。
        scale =
            ClickableSurfaceDefaults.scale(
                focusedScale = 1f,
                pressedScale = 1f,
            ),
        // 与工程其它可选行同一口径（见 core/focus/focusInvertedColors）：
        // 当前播放集用**主色实底 + onPrimary 文字**（35% 透明太弱，用户看不出在播哪集），
        // 其余行淡底条带；聚焦时整体反色成「浅底深字」。
        colors =
            focusInvertedColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        ITEM_IDLE_CONTAINER
                    },
                contentColor =
                    if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            ),
    ) {
        Text(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ITEM_HORIZONTAL_PADDING, vertical = 10.dp),
            text = text,
            // 播放器内弹层统一用 titleLarge（见 MenuListItem）
            style = MaterialTheme.typography.titleLarge,
            textAlign = textAlign,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// region Previews

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun VideoListControllerPreview() {
    val sampleList =
        listOf(
            VideoListItem(aid = 1, cid = 101, title = "第 1 集 · 序幕"),
            VideoListItem(aid = 1, cid = 102, title = "第 2 集 · 出发"),
            VideoListItem(aid = 1, cid = 103, title = "第 3 集 · 迷雾"),
        )

    dev.frost819.newbv.core.theme.BVTheme {
        VideoListController(
            modifier = Modifier.padding(16.dp),
            currentCid = 102,
            videoList = sampleList,
            onPlayNewVideo = {},
        )
    }
}

// endregion
