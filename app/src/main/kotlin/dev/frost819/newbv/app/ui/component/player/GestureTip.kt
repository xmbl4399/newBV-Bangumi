package dev.frost819.newbv.app.ui.component.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.frost819.newbv.app.util.formatHourMinSec

/** 提示底色：上圆与下胶囊共用同一透明度。 */
private val TIP_CONTAINER = Color.Black.copy(alpha = 0.6f)

/**
 * 提示圆形的边长。
 *
 * 用**固定正方形**而不是"内容自适应 + 圆形裁剪"：后者在"图标 + 数值"竖排时
 * 高度大于宽度，[CircleShape] 只能裁出椭圆。取 112dp 既容得下 32dp 图标 + 数值，
 * 也容得下 Seek 的「快进/快退」四字。
 */
private val TIP_CIRCLE_SIZE = 112.dp

/** 图标与数值之间的间距：取小值让两者靠紧，整体更接近正圆。 */
private val TIP_ICON_TEXT_GAP = 2.dp

/**
 * 手势提示覆盖层。
 *
 * 在手势执行期间显示反馈（亮度/音量百分比、倍速值、seek 状态）。
 * 与 [PlayStateTips]/[SkipTips] 平级，放在 [VideoPlayerController] 覆盖层中。
 *
 * 版式：**圆形**内只有图标与数值（两者靠紧，保证是正圆）；长按倍速的
 * 「当前进度 / 总进度」放在圆形下方，用**同透明度的胶囊底**承托。
 *
 * @param state 手势提示状态。
 * @param modifier 修饰符。
 * @param positionMs 当前播放进度（毫秒），负值表示未知；仅长按倍速时展示。
 * @param durationMs 视频总时长（毫秒），为 0 表示未知；仅长按倍速时展示。
 */
@Composable
fun GestureTip(
    state: GestureTipState,
    modifier: Modifier = Modifier,
    positionMs: Long = -1L,
    durationMs: Long = 0L,
) {
    AnimatedVisibility(
        visible = state.isActive,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val iconVector =
                when (state.type) {
                    GestureTipType.Brightness -> Icons.Rounded.BrightnessHigh
                    GestureTipType.Volume -> Icons.AutoMirrored.Rounded.VolumeUp
                    GestureTipType.Speed -> Icons.Rounded.Speed
                    GestureTipType.Seek -> Icons.Rounded.FastForward
                    GestureTipType.None -> null
                }
            val displayText =
                when (state.type) {
                    GestureTipType.Brightness -> "${(state.value * 100).toInt()}%"
                    GestureTipType.Volume -> "${state.value.toInt()}%"
                    GestureTipType.Speed -> "${state.value}x"
                    GestureTipType.Seek -> "快进/快退"
                    GestureTipType.None -> ""
                }

            Box(
                modifier =
                    Modifier
                        .size(TIP_CIRCLE_SIZE)
                        .clip(CircleShape)
                        .background(TIP_CONTAINER),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(TIP_ICON_TEXT_GAP),
                ) {
                    if (iconVector != null) {
                        Icon(
                            imageVector = iconVector,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    if (displayText.isNotEmpty()) {
                        Text(
                            text = displayText,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                    }
                }
            }

            // 进度时间：圆形下方，胶囊底（与圆形同透明度），随播放实时刷新
            if (state.type == GestureTipType.Speed && positionMs >= 0L && durationMs > 0L) {
                Text(
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .background(TIP_CONTAINER)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    text = "${positionMs.formatHourMinSec()} / ${durationMs.formatHourMinSec()}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
            }
        }
    }
}
