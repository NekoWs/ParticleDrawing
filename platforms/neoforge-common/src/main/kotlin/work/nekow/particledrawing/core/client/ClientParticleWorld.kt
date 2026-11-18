package work.nekow.particledrawing.core.client

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import work.nekow.particledrawing.animation.recipe.ParticleWorld

/**
 * 客户端的世界查询：粒子在世界里的位置要能撞到方块。
 *
 * 粒子位置本身是客户端模拟出来的（服务端只同步声明，不逐颗计算），
 * 所以碰撞也在客户端判定，和渲染看到的世界完全一致。
 *
 * 「实心」按碰撞箱判，而不是按「是否完整方块」：台阶、栅栏、树叶都能挡粒子，
 * 和玩家走过去时的手感一致。
 */
internal object ClientParticleWorld : ParticleWorld {

    override fun isSolid(x: Double, y: Double, z: Double): Boolean {
        val level = Minecraft.getInstance().level ?: return false
        val pos = BlockPos.containing(x, y, z)
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty
    }

    override fun isLiquid(x: Double, y: Double, z: Double): Boolean {
        val level = Minecraft.getInstance().level ?: return false
        val pos = BlockPos.containing(x, y, z)
        return level.getBlockState(pos).liquid()
    }
}