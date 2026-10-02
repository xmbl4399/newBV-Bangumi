package dev.frost819.newbv.app.ui.component.videocard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.frost819.newbv.app.ui.component.SkeletonBox

/** 封面宽高比：必须与 [BangumiCard] 一致，否则骨架切换成真数据时会跳版。 */
private const val COVER_ASPECT_RATIO = 0.72f

/** 标题占位条的高度（真标题是 14sp 单行，条短一些更像文字）。 */
private val TITLE_BAR_HEIGHT = 12.dp

/**
 * Bangumi 卡片骨架屏。
 *
 * 目的是**让首屏等待期有形状**，而不是转圈或一行进度文字。
 *
 * 尺寸与 [BangumiCard] 逐项对齐（封面同比例、标题同 `titleSmall` 行高、同 6dp 内边距），
 * 这样数据到达时是「填充」而不是「重排」。标题那一行用一段**透明文字**撑出真实行高，
 * 再叠一条短的微光条 —— 硬编码行高数字会在换字体/改字号后立刻错位。
 *
 * @param modifier Modifier。
 */
@Composable
fun BangumiSkeletonCard(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surface),
    ) {
        Column {
            SkeletonBox(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(COVER_ASPECT_RATIO),
                shape = MaterialTheme.shapes.large,
            )

            Box(modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
                // 透明文字只用于占位撑高：真实的 titleSmall 行高由字体决定，猜不准
                Text(
                    text = "占位",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    color = Color.Transparent,
                )
                SkeletonBox(
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxWidth(0.72f)
                            .height(TITLE_BAR_HEIGHT),
                )
            }
        }
    }
}

/**
 * 月份分割标题的骨架占位。
 *
 * 高度与 `MonthDivider` 的 18sp 行高对齐，宽度按「9月-2部」这类文本目测取屏宽 6%。
 *
 * @param modifier Modifier。
 */
@Composable
fun MonthDividerSkeleton(modifier: Modifier = Modifier) {
    SkeletonBox(
        modifier =
            modifier
                .height(18.dp)
                .fillMaxWidth(0.06f),
    )
}
