package dev.frost819.newbv.app.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme

/** 微光扫过一轮的时长。 */
private const val SHIMMER_DURATION_MILLIS = 1200

/**
 * 骨架屏占位块（带微光扫过动画）。
 *
 * 底色取 `surfaceVariant`，高光带用 `onSurface` 的低透明度叠上去 ——
 * 这样**深浅主题都不额外判断**：浅色主题下是「浅灰底 + 略深的高光带」，
 * 深色主题下是「深灰底 + 略亮的高光带」，两边都是合适的微光。
 *
 * 高光带按控件**实测宽度**计算位移（[drawWithCache] 里拿 `size.width`），
 * 因此不同列宽的网格里速度一致，不会出现窄控件上扫得飞快的情况。
 *
 * @param modifier Modifier（尺寸由调用方给）。
 * @param shape 圆角形状。
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(4.dp),
) {
    val baseColor = MaterialTheme.colorScheme.surfaceVariant
    val highlightColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val transition = rememberInfiniteTransition(label = "skeleton-shimmer")
    val progress by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = SHIMMER_DURATION_MILLIS, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "skeleton-shimmer-progress",
        )

    Box(
        modifier =
            modifier
                .clip(shape)
                .background(baseColor)
                .drawWithCache {
                    // 高光带宽度取控件宽度的 70%，两端各留出带外空白，避免扫到边缘时突兀
                    val bandWidth = size.width * 0.7f
                    val startX = progress * (size.width + bandWidth) - bandWidth
                    val brush =
                        Brush.linearGradient(
                            colors =
                                listOf(
                                    Color.Transparent,
                                    highlightColor,
                                    Color.Transparent,
                                ),
                            start = Offset(startX, 0f),
                            end = Offset(startX + bandWidth, 0f),
                        )
                    onDrawBehind { drawRect(brush = brush) }
                },
    )
}
