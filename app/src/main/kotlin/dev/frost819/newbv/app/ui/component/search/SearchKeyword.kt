package dev.frost819.newbv.app.ui.component.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.DenseListItem
import androidx.tv.material3.Text
import dev.frost819.newbv.core.focus.touchClickable

/**
 * 搜索关键词列表项。
 *
 * 显示关键词文本，点击触发搜索。
 *
 * TV Material3 的 [DenseListItem] 内部用 `tvSelectable` 只处理 D-Pad 确认键，
 * 不含 `pointerInput`，因此必须额外挂 [touchClickable] 才能用手指点动
 * —— 热搜、搜索建议、搜索历史三列共用本组件，一处补齐三处生效。
 *
 * @param keyword 关键词文本
 * @param onClick 点击回调
 * @param trailingIcon 尾部图标（删除模式下使用）
 */
@Composable
fun SearchKeyword(
    modifier: Modifier = Modifier,
    keyword: String,
    onClick: () -> Unit,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    DenseListItem(
        modifier = modifier.touchClickable(onClick = onClick),
        selected = false,
        onClick = onClick,
        headlineContent = {
            Text(
                text = keyword,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = trailingIcon,
    )
}
