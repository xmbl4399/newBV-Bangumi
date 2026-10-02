package dev.frost819.newbv.app.ui.component.player

import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * [GestureStepAccumulator] 与 [adjustVolume] 的单元测试。
 *
 * 重点覆盖实测出的真实缺陷：右半屏慢慢拖动时，单个手势事件的位移不足一档，
 * 逐事件取整会把整段位移吞掉（表现为「快速滑能调、慢慢拖没反应」）。
 * 累积后音量响应应当只与**总位移**有关，与拖动速度无关。
 */
class PlayerGestureHandlerTest {
    @Test
    fun `accumulator accumulates sub-step deltas until one step is reached`() {
        val accumulator = GestureStepAccumulator(stepPx = 80f)

        // 每事件 20px：前三次都不足一步，第四次累积到 80px 才出一步
        assertThat(accumulator.steps(20f)).isEqualTo(0)
        assertThat(accumulator.steps(20f)).isEqualTo(0)
        assertThat(accumulator.steps(20f)).isEqualTo(0)
        assertThat(accumulator.steps(20f)).isEqualTo(1)
    }

    @Test
    fun `accumulator emits multiple steps for a fast drag and keeps the remainder`() {
        val accumulator = GestureStepAccumulator(stepPx = 80f)

        assertThat(accumulator.steps(200f)).isEqualTo(2)
        // 余量 40px 保留到下一次，避免每次取整都丢掉零头
        assertThat(accumulator.steps(40f)).isEqualTo(1)
    }

    @Test
    fun `accumulator keeps the direction of a slow drag`() {
        val accumulator = GestureStepAccumulator(stepPx = 80f)

        assertThat(accumulator.steps(-30f)).isEqualTo(0)
        assertThat(accumulator.steps(-30f)).isEqualTo(0)
        assertThat(accumulator.steps(-30f)).isEqualTo(-1)
    }

    @Test
    fun `adjustVolume applies steps to the music stream`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 4

        val percent = adjustVolume(audioManager, deltaSteps = 3)

        verify { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 7, AudioManager.FLAG_SHOW_UI) }
        assertThat(percent).isEqualTo(46)
    }

    @Test
    fun `adjustVolume clamps at the upper bound without writing`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 15

        val percent = adjustVolume(audioManager, deltaSteps = 5)

        assertThat(percent).isEqualTo(100)
        // 已在最大值：不产生无意义的系统写调用
        verify(exactly = 0) { audioManager.setStreamVolume(any(), any(), any()) }
    }

    @Test
    fun `adjustVolume with zero steps only reads the current value`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 3

        val percent = adjustVolume(audioManager, deltaSteps = 0)

        assertThat(percent).isEqualTo(20)
        verify(exactly = 0) { audioManager.setStreamVolume(any(), any(), any()) }
    }

    @Test
    fun `adjustVolume clamps at the lower bound`() {
        val audioManager = mockk<AudioManager>(relaxed = true)
        every { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } returns 15
        every { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) } returns 1

        val percent = adjustVolume(audioManager, deltaSteps = -5)

        verify { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI) }
        assertThat(percent).isEqualTo(0)
    }
}
