package dev.frost819.newbv.app.ui.component.player

import android.app.Activity
import android.media.AudioManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs

/**
 * 手势状态，用于驱动 [GestureTip] 覆盖层显示。
 *
 * @property isActive 手势是否正在执行中（用于显示提示）。
 * @property type 当前手势类型。
 * @property value 当前手势值（亮度百分比 0~1、音量百分比 0~100、倍速值）。
 */
data class GestureTipState(
    val isActive: Boolean = false,
    val type: GestureTipType = GestureTipType.None,
    val value: Float = 0f,
)

/**
 * 手势提示类型。
 */
enum class GestureTipType {
    None,
    Brightness,
    Volume,
    Speed,
    Seek,
}

/**
 * 长按判定的等待时间（毫秒）：超过它仍未移动即视为长按。
 */
const val LONG_PRESS_TIMEOUT_MS = 500L

/**
 * 播放器手势回调。
 *
 * @param onSingleTap 单击：显示/隐藏控制器。
 * @param onDoubleTap 双击：播放/暂停。
 * @param onSeekDelta 水平拖拽 seek：正值快进、负值快退（毫秒增量）。
 * @param onSeekCommit seek 提交（手指松开时调用）。
 * @param onBrightnessChange 亮度变化：deltaY > 0 增加亮度，< 0 降低亮度。
 * @param onVolumeChange 音量变化：deltaY > 0 增加音量，< 0 降低音量。
 * @param onLongPressStart 长按开始：`isLeftHalf` 为 true 表示左半屏（快退），false 为右半屏（快进）。
 * @param onLongPressEnd 长按结束（手指抬起）：调用方负责恢复倍速 / 停止快退。
 */
data class PlayerGestureCallbacks(
    val onSingleTap: () -> Unit,
    val onDoubleTap: () -> Unit,
    val onSeekDelta: (deltaMs: Long) -> Unit,
    val onSeekCommit: () -> Unit,
    val onBrightnessChange: (deltaY: Float) -> Unit,
    val onVolumeChange: (deltaY: Float) -> Unit,
    val onLongPressStart: (isLeftHalf: Boolean) -> Unit = {},
    val onLongPressEnd: () -> Unit = {},
)

/**
 * 播放器手势处理器。
 *
 * 使用 `awaitEachGesture` 手动分发 6 种手势（PRD 4.3.3.2）：
 * - 单击：显示/隐藏控制器
 * - 双击：播放/暂停
 * - 水平滑动：快进/快退
 * - 左半屏垂直滑动：亮度调节
 * - 右半屏垂直滑动：音量调节（写系统媒体音量并弹系统音量条）
 * - **长按**：右半屏 3× 快进、左半屏 3× 快退（见 [PlayerGestureCallbacks.onLongPressStart]）
 *
 * D-pad 模式不受影响——此 modifier 仅处理触摸事件，按键事件由 `onPreviewKeyEvent` 处理。
 *
 * @param totalDuration 视频总时长（毫秒），用于将像素位移转换为时间增量。
 * @param controllerVisible 控制器（信息栏+进度条）是否可见。可见时水平拖拽交给进度条处理。
 * @param enabled 手势是否生效。覆盖层（选集/相关视频整合页）打开时应返回 false：
 *   否则在页面上滑动会同时触发视频的亮度/音量手势。默认始终生效。
 * @param callbacks 手势回调。
 * @param gestureTipState 手势提示状态（外部持有，用于驱动覆盖层 UI）。
 */
@Composable
fun rememberGestureTipState(): androidx.compose.runtime.MutableState<GestureTipState> =
    remember { mutableStateOf(GestureTipState()) }

/**
 * 播放器手势 Modifier 扩展。
 *
 * 使用 `awaitEachGesture` 手动分发触摸事件，避免多个 `detectXxxGestures` 互相消费事件。
 *
 * @param totalDuration 视频总时长（毫秒）。
 * @param controllerVisible 控制器（信息栏+进度条）是否可见。
 * @param enabled 手势是否生效（覆盖层打开时置 false）。
 * @param callbacks 手势回调集合。
 * @param gestureTipState 手势提示状态（外部持有）。
 */
fun Modifier.playerGestures(
    totalDuration: () -> Long,
    controllerVisible: () -> Boolean,
    callbacks: PlayerGestureCallbacks,
    gestureTipState: androidx.compose.runtime.MutableState<GestureTipState>,
    enabled: () -> Boolean = { true },
): Modifier =
    this.pointerInput(Unit) {
        val doubleTapTimeout = 300L
        val tapSlop = 40f
        val dragThreshold = 10f

        var lastTapTime = 0L

        awaitEachGesture {
            val firstDown = awaitFirstDown(requireUnconsumed = false)
            // 覆盖层打开时不参与视频手势：事件留给页面自己的点击/滚动
            if (!enabled()) return@awaitEachGesture

            val startTime = System.currentTimeMillis()
            val startX = firstDown.position.x
            val startY = firstDown.position.y
            val width = this.size.width.toFloat()

            var isDragging = false
            var totalDeltaX = 0f
            var totalDeltaY = 0f
            var isHorizontalDrag: Boolean? = null
            var isLeftHalf = startX < width / 2

            /*
             * 长按探测改为**事件驱动**（awaitLongPressOrCancellation），不再用
             * 「500ms 内没有 pointer 事件就算长按」的定时器写法。
             *
             * 旧写法为什么导致 3× 快进失效：`withTimeoutOrNull(500)` 包住
             * `awaitPointerEvent`，只有当指针事件**完全不来**时才会超时。
             * 但手指按在屏幕上（哪怕自认为没动）仍会持续产生 move 事件，
             * 每次都在 500ms 内返回 ⇒ 永不超时 ⇒ longPressTriggered 恒为 false
             * ⇒ onLongPressStart() 永不触发。实测确认：长按右半屏无任何反应，
             * 而单击唤出控件正常（说明事件通道本身是通的，只有长按分支不可达）。
             *
             * awaitLongPressOrCancellation 由 Compose 内部按真实超时判定，
             * 并把「按住了但发生移动」识别为取消（返回 null），因此抖动手感也正确
             * （拖拽/亮度/音量手势不会被误判成长按）。
             */
            val longPress =
                awaitLongPressOrCancellation(pointerId = firstDown.id)

            if (longPress != null) {
                isLeftHalf = startX < width / 2
                callbacks.onLongPressStart(isLeftHalf)

                // 长按期间只等抬起：不再做位移判定，避免长按变成亮度/音量调节
                while (true) {
                    val upEvent = awaitPointerEvent(PointerEventPass.Initial)
                    val upChange = upEvent.changes.firstOrNull { it.id == firstDown.id } ?: break
                    if (!upChange.pressed) {
                        upChange.consume()
                        break
                    }
                    // 长按成立后，指针移动不再产生任何手势语义，但仍消费掉，
                    // 防止事件穿透到下层视频手势
                    upChange.consume()
                }

                callbacks.onLongPressEnd()
                gestureTipState.value = GestureTipState(isActive = false)
                return@awaitEachGesture
            }

            var longPressTriggered = false

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val changes = event.changes
                val change = changes.firstOrNull() ?: continue

                if (!change.pressed) {
                    // 手指抬起
                    // 如果事件已被子组件消费（如按钮点击），跳过手势处理
                    if (change.isConsumed) break

                    // 长按分支已在前面（awaitLongPressOrCancellation）单独处理并 return，
                    // 走不到这里
                    if (isDragging) {
                        if (isHorizontalDrag == true) {
                            callbacks.onSeekCommit()
                        }
                        gestureTipState.value = GestureTipState(isActive = false)
                    } else {
                        // 判断是否为 tap
                        val moved =
                            abs(change.position.x - startX) > tapSlop ||
                                abs(change.position.y - startY) > tapSlop
                        if (!moved) {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < doubleTapTimeout) {
                                callbacks.onDoubleTap()
                                lastTapTime = 0L
                            } else {
                                lastTapTime = now
                                callbacks.onSingleTap()
                            }
                        }
                    }
                    change.consume()
                    break
                }

                // 如果事件已被子组件消费（如进度条拖拽），跳过移动处理
                if (change.isConsumed) continue

                // 手指移动中
                if (change.positionChanged()) {
                    val deltaX = change.positionChange().x
                    val deltaY = change.positionChange().y
                    totalDeltaX += deltaX
                    totalDeltaY += deltaY

                    val absX = abs(totalDeltaX)
                    val absY = abs(totalDeltaY)

                    // 判断拖拽方向（仅首次超过阈值时）
                    if (isHorizontalDrag == null && (absX > dragThreshold || absY > dragThreshold)) {
                        isHorizontalDrag = absX > absY
                        isDragging = true
                    }

                    if (isHorizontalDrag == true) {
                        // 水平拖拽 → seek
                        val durationMs = totalDuration()
                        if (durationMs > 0) {
                            val deltaMs = (deltaX / width * durationMs * 0.5f).toLong()
                            if (deltaMs != 0L) {
                                callbacks.onSeekDelta(deltaMs)
                                gestureTipState.value =
                                    GestureTipState(
                                        isActive = true,
                                        type = GestureTipType.Seek,
                                    )
                            }
                        }
                    } else if (isHorizontalDrag == false) {
                        // 垂直拖拽 → 亮度/音量
                        // Compose 中 y 向下为正，上滑（deltaY < 0）应增加亮度/音量，故取反
                        isLeftHalf = startX < width / 2
                        if (isLeftHalf) {
                            callbacks.onBrightnessChange(-deltaY)
                        } else {
                            callbacks.onVolumeChange(-deltaY)
                        }
                    }
                    change.consume()
                }
            }
        }
    }

/**
 * 亮度调节辅助函数。
 *
 * 通过修改 Activity 窗口的 `screenBrightness` 控制亮度。
 *
 * @param activity 当前 Activity。
 * @param deltaY 垂直位移增量（正值增加亮度，负值降低亮度）。
 * @param currentBrightness 当前亮度值（0~1），-1 表示系统默认。
 * @return 调整后的亮度值（0~1）。
 */
fun adjustBrightness(
    activity: Activity,
    deltaY: Float,
    currentBrightness: Float,
): Float {
    val newBrightness =
        if (currentBrightness < 0) {
            0.5f + deltaY / 1000f
        } else {
            currentBrightness + deltaY / 1000f
        }
    val clamped = newBrightness.coerceIn(0.01f, 1f)
    val layoutParams = activity.window.attributes
    layoutParams.screenBrightness = clamped
    activity.window.attributes = layoutParams
    return clamped
}

/**
 * 音量手势每档对应的一屏垂直位移（px）。
 *
 * 实测 1280×720 上取 80px：整屏高度约合 9 档，与系统音量默认 15 档的手感接近。
 */
const val VOLUME_GESTURE_STEP_PX = 80f

/**
 * 步进累积器。
 *
 * 手势回调是**按事件**给位移的，而慢速拖动时单个事件的位移往往不足一个步进阈值
 * （实测右半屏 2000ms 拖完 400px，逐事件位移只有几 px）。若逐事件取整，整段位移
 * 会被吞掉 —— 表现为「快速滑一下能调、慢慢拖完全没反应」。
 *
 * 这里把不足一步的余量跨事件累积，凑够一步才返回步数，使音量响应只与**总位移**
 * 有关、与拖动速度无关。
 *
 * @param stepPx 一步对应的位移量（同正负号体系）
 */
class GestureStepAccumulator(private val stepPx: Float) {
    private var pending = 0f

    /**
     * 累积本次位移，返回应执行的步数。
     *
     * @param delta 本次事件的位移（正负表示方向），不足一步时返回 0
     */
    fun steps(delta: Float): Int {
        pending += delta
        val whole = (pending / stepPx).toInt()
        if (whole != 0) pending -= whole * stepPx
        return whole
    }
}

/**
 * 按步数调整系统媒体音量，并**弹出系统音量条**。
 *
 * 直接写 `STREAM_MUSIC` 的流音量（作用于整个系统媒体输出），并带
 * [AudioManager.FLAG_SHOW_UI] 让系统自己显示音量面板 —— UI 侧因此不再画自定义音量提示。
 *
 * @param audioManager 系统音频管理器
 * @param deltaSteps 音量步数（正数增大、负数减小）；为 0 时只读取当前值
 * @return 调整后的音量百分比（0~100）
 */
fun adjustVolume(
    audioManager: AudioManager,
    deltaSteps: Int,
): Int {
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val targetVolume = (currentVolume + deltaSteps).coerceIn(0, maxVolume)
    if (targetVolume != currentVolume) {
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            targetVolume,
            AudioManager.FLAG_SHOW_UI,
        )
    }
    return if (maxVolume > 0) (targetVolume.toFloat() / maxVolume.toFloat() * 100).toInt() else 0
}
