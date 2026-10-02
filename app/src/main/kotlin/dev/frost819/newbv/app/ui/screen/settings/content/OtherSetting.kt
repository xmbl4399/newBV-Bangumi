package dev.frost819.newbv.app.ui.screen.settings.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dev.frost819.newbv.BuildConfig
import dev.frost819.newbv.app.ui.component.FocusSaver
import dev.frost819.newbv.app.ui.component.focusSaverItem
import dev.frost819.newbv.app.ui.component.settings.OptionDialog
import dev.frost819.newbv.app.ui.component.settings.SettingListItem
import dev.frost819.newbv.app.ui.component.settings.displayName
import dev.frost819.newbv.app.ui.screen.settings.SettingsMenuNavItem
import dev.frost819.newbv.bangumiapi.entity.BangumiApiSource
import dev.frost819.newbv.data.datastore.ApiType
import dev.frost819.newbv.data.datastore.Prefs

/**
 * 其他设置页。
 *
 * 接口选择/崩溃上报/查看日志，以及 Bangumi 相关开关
 * （隐藏无评分条目、BGM 接口设置）。
 *
 * @param onNavigateToLogViewer 跳转日志查看页回调。
 */
@Composable
fun OtherSetting(
    modifier: Modifier = Modifier,
    onNavigateToLogViewer: () -> Unit = {},
    screenFocusSaver: FocusSaver? = null,
) {
    val scrollState = rememberScrollState()

    var showPreferedApiDialog by remember { mutableStateOf(false) }
    var selectedApi by remember { mutableStateOf(Prefs.apiType) }
    var crashReportEnabled by remember { mutableStateOf(Prefs.crashReportEnabled) }
    var hideNoScoreEnabled by remember { mutableStateOf(Prefs.hideNoScoreMedia) }
    var showBangumiApiDialog by remember { mutableStateOf(false) }
    var selectedBangumiApi by remember { mutableStateOf(Prefs.bangumiApiBaseUrl) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = SettingsMenuNavItem.Other.displayName,
            style = MaterialTheme.typography.displaySmall,
        )
        Spacer(modifier = Modifier.height(12.dp))

        SettingListItem(
            title = "接口选择",
            supportText = "当前：${selectedApi.displayName}",
            onClick = { showPreferedApiDialog = true },
        )

        SettingListItem(
            title = "BGM 接口设置",
            supportText = "当前：${BangumiApiSource.fromBaseUrl(selectedBangumiApi).displayName}" +
                "（官方不通时自动切换备用源）",
            onClick = { showBangumiApiDialog = true },
        )

        SettingListItem(
            title = "隐藏无评分条目",
            supportText = "Bangumi 分类页只保留已有评分的条目",
            trailingContent = {
                Switch(
                    checked = hideNoScoreEnabled,
                    onCheckedChange = {
                        hideNoScoreEnabled = it
                        Prefs.hideNoScoreMedia = it
                    },
                )
            },
            onClick = {
                hideNoScoreEnabled = !hideNoScoreEnabled
                Prefs.hideNoScoreMedia = hideNoScoreEnabled
            },
        )

        SettingListItem(
            title = "上传崩溃日志",
            supportText = "崩溃时自动上传日志到开发者服务器",
            trailingContent = {
                Switch(
                    checked = crashReportEnabled,
                    onCheckedChange = {
                        crashReportEnabled = it
                        Prefs.crashReportEnabled = it
                    },
                )
            },
            onClick = {
                crashReportEnabled = !crashReportEnabled
                Prefs.crashReportEnabled = crashReportEnabled
            },
        )

        if (BuildConfig.DEBUG) {
            SettingListItem(
                title = "触发测试崩溃",
                supportText = "仅用于测试崩溃上传流程",
                onClick = { throw RuntimeException("Test crash for upload verification") },
            )
        }

        SettingListItem(
            modifier = screenFocusSaver?.let { Modifier.focusSaverItem(it, "content_log_viewer") } ?: Modifier,
            title = "查看日志",
            supportText = "查看崩溃日志和应用日志，支持扫码下载",
            onClick = onNavigateToLogViewer,
        )
    }

    if (showPreferedApiDialog) {
        OptionDialog(
            options = ApiType.entries.toTypedArray(),
            selectedOption = selectedApi,
            onDismiss = { showPreferedApiDialog = false },
            onSelect = {
                Prefs.apiType = it
                selectedApi = it
            },
            getDisplayName = { it.displayName },
        )
    }

    if (showBangumiApiDialog) {
        OptionDialog(
            options = BangumiApiSource.entries.toTypedArray(),
            selectedOption = BangumiApiSource.fromBaseUrl(selectedBangumiApi),
            onDismiss = { showBangumiApiDialog = false },
            onSelect = {
                Prefs.bangumiApiBaseUrl = it.baseUrl
                selectedBangumiApi = it.baseUrl
            },
            getDisplayName = { it.displayName },
        )
    }
}
