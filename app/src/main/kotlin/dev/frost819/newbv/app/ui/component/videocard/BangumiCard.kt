package dev.frost819.newbv.app.ui.component.videocard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.frost819.newbv.core.focus.touchClickable

/** 高分评分的强调色（金色）。 */
private val SCORE_GOLD = Color(0xFFFFC107)

/** 封面宽高比：Bangumi 竖版封面约 1:1.4。 */
private const val COVER_ASPECT_RATIO = 0.72f

/**
 * 角标底色：半透明黑。
 *
 * 0.75 而不是 0.62 —— 封面上常有白色标题、天空、灯牌这类亮部，
 * 底色太透时白字会与亮部糊在一起。
 */
private val CHIP_BACKGROUND = Color.Black.copy(alpha = 0.75f)

/** 角标描边：浅白细边，让胶囊在深色封面上也能看出边界。 */
private val CHIP_BORDER_COLOR = Color.White.copy(alpha = 0.22f)

/** 角标描边宽度。 */
private val CHIP_BORDER_WIDTH = 1.dp

/** 角标文字阴影：把白字从封面花纹里再拔出来一层。 */
private val CHIP_TEXT_SHADOW = Shadow(color = Color.Black.copy(alpha = 0.9f), blurRadius = 3f)

/** 角标圆角。 */
private val CHIP_CORNER = 6.dp

/**
 * 角标字号（tag / 集数）。
 *
 * 封面越切越窄（6 列约 176dp、7 列约 150dp）反而越需要放大字号 ——
 * 10sp 那版在电视上要凑近才看清，统一放大一号到 12sp。
 */
private val CHIP_FONT_SIZE = 12.sp

/** 评分字号：比 tag / 集数再大一号 —— 评分是滑动浏览时最主要的筛选依据。 */
private val SCORE_FONT_SIZE = 14.sp

/** 评分徽章底色渐变：比 tag 更实，保证金色/白色数字在任意封面上都立得住。 */
private val SCORE_GRADIENT =
    listOf(Color.Black.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.68f))

/**
 * Bangumi 番剧卡片。
 *
 * 版式（照搬 blbl-Bangumi 的信息密度，每行 6 个封面时仍可读）：
 * - 封面（竖版）四个角承载全部元信息：**左上流派 tag（最多两个胶囊上下叠放）、
 *   左下总话数、右上评分**；
 * - 封面下方只留标题（单行省略）。
 * - 所有角标的底色**只包住文字**，文字短则胶囊短。
 *
 * 把 tag 与话数从封面下方搬到封面角上，是为了在 6 列布局里把纵向空间让给标题；
 * 卡片高度只剩「封面 + 一行标题」，一屏能看到两行以上封面。
 *
 * 无评分时**不渲染徽章**（接口无评分时给 0.0，直接显示会变成「0.0 分」）；
 * 无 tag / 无话数时不占位（冷门条目字段缺失是数据本身的问题，补不出来）。
 *
 * @param data 卡片数据。
 * @param onClick 点击回调。
 * @param modifier Modifier。
 */
@Composable
fun BangumiCard(
    data: BangumiCardData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.touchClickable(onClick = onClick),
        onClick = onClick,
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                pressedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.large),
        border =
            ClickableSurfaceDefaults.border(
                focusedBorder =
                    Border(
                        border = BorderStroke(width = 3.dp, color = MaterialTheme.colorScheme.border),
                        shape = MaterialTheme.shapes.large,
                    ),
            ),
    ) {
        Column {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large),
            ) {
                AsyncImage(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(COVER_ASPECT_RATIO)
                            .clip(MaterialTheme.shapes.large),
                    model = data.cover,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                )

                // 左上：流派 tag —— 每个 tag 一个胶囊，自上而下叠放（最多 2 个）。
                // 原来把多个 tag 拼成一行「A · B」，胶囊被拉满封面宽度，背景比文字长出一大截。
                if (data.tags.isNotEmpty()) {
                    Column(
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        data.tags.forEach { tag ->
                            CoverChip(
                                text = tag,
                                maxWidthFraction = if (data.scoreText != null) 0.66f else 1f,
                            )
                        }
                    }
                }

                // 左下：总话数
                if (data.hasEpCount) {
                    CoverChip(
                        modifier =
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(6.dp),
                        text = "全 ${data.epCount} 话",
                    )
                }

                // 右上：评分
                if (data.scoreText != null) {
                    ScoreBadge(
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp),
                        scoreText = data.scoreText,
                        highlighted = data.isHighScore,
                    )
                }
            }

            Text(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                text = data.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 封面上的信息角标。
 *
 * **背景宽度跟着文字走**：只给一个"最大可占封面比例"的约束，文字短时胶囊就短。
 * 早前用 `fillMaxWidth(fraction)` 直接把胶囊拉满固定比例，于是「全 12 话」这种
 * 三四个字的角标也拖着一条长黑条，视觉上像标题栏而不是角标。
 *
 * @param text 角标文本。
 * @param maxWidthFraction 最大宽度占封面的比例（给右上角评分徽章让位时收窄）。
 */
@Composable
private fun CoverChip(
    text: String,
    modifier: Modifier = Modifier,
    maxWidthFraction: Float = 1f,
) {
    val shape = RoundedCornerShape(CHIP_CORNER)
    BoxWithConstraints(modifier = modifier) {
        Box(
            modifier =
                Modifier
                    .widthIn(max = maxWidth * maxWidthFraction)
                    .clip(shape)
                    .background(CHIP_BACKGROUND)
                    .border(CHIP_BORDER_WIDTH, CHIP_BORDER_COLOR, shape),
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = CHIP_FONT_SIZE, shadow = CHIP_TEXT_SHADOW),
                color = Color.White,
            )
        }
    }
}

/**
 * 评分徽章。
 *
 * 底色统一为半透明黑以压住封面亮部；高分（≥7）用金色文字 + 金色描边，
 * 普通分数用白字 + 白色细描边 —— 描边是为了在浅色封面上仍能看清边界。
 *
 * @param scoreText 一位小数的评分文本。
 * @param highlighted 是否高分（金色）。
 */
@Composable
private fun ScoreBadge(
    scoreText: String,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = if (highlighted) SCORE_GOLD else Color.White
    val shape = RoundedCornerShape(CHIP_CORNER)
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(Brush.verticalGradient(colors = SCORE_GRADIENT))
                .border(CHIP_BORDER_WIDTH, CHIP_BORDER_COLOR, shape),
    ) {
        Text(
            modifier =
                Modifier
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            text = scoreText,
            style =
                TextStyle(
                    fontSize = SCORE_FONT_SIZE,
                    fontWeight = FontWeight.Bold,
                    shadow = CHIP_TEXT_SHADOW,
                ),
            color = accent,
        )
    }
}
