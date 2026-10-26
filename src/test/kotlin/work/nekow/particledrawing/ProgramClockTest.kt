package work.nekow.particledrawing

import work.nekow.particledrawing.core.client.ClientAnimationProgramManager
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 编排程序的时钟：迟到补发必须保留原起点，不能把接收时刻当成程序起点。
 */
class ProgramClockTest {

    @Test
    fun `首次下发从原起点开始`() {
        val start = 20L
        val received = 20L
        val offset = start - received
        assertEquals(0L, ClientAnimationProgramManager.programElapsedMs(received, offset, start))
        assertEquals(200L, ClientAnimationProgramManager.programElapsedMs(24L, offset, start))
    }

    @Test
    fun `迟到补发保留已经过的相位`() {
        val start = 20L
        val sent = 24L
        val received = 20L
        val offset = sent - received
        // 收到时服务端已经跑了 4t；之后按客户端时钟继续走。
        assertEquals(200L, ClientAnimationProgramManager.programElapsedMs(received, offset, start))
        assertEquals(400L, ClientAnimationProgramManager.programElapsedMs(24L, offset, start))
    }

    @Test
    fun `时钟对齐时迟到程序仍从原起点计时`() {
        val start = 20L
        val sent = 48L
        val received = 48L
        val offset = sent - received
        assertEquals(1_400L, ClientAnimationProgramManager.programElapsedMs(received, offset, start))
    }
}
