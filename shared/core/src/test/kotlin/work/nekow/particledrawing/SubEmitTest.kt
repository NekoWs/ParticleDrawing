package work.nekow.particledrawing

import work.nekow.particledrawing.api.ParticleRecipe
import work.nekow.particledrawing.api.ParticleSeed
import work.nekow.particledrawing.api.SubEmit
import work.nekow.particledrawing.math.Vec3
import kotlin.test.Test
import kotlin.test.assertFails

/** 子发射参数范围：数量与速度上限是为了防止一次死亡炸出不可控的粒子。 */
class SubEmitTest {

    private fun recipe(sub: SubEmit) = ParticleRecipe(listOf(ParticleSeed(Vec3.ZERO)), subEmit = sub)

    @Test
    fun `默认参数合法`() {
        recipe(SubEmit()).validate()
    }

    @Test
    fun `数量超出上限被拒绝`() {
        assertFails { recipe(SubEmit(count = SubEmit.MAX_COUNT + 1)).validate() }
    }

    @Test
    fun `速度为负被拒绝`() {
        assertFails { recipe(SubEmit(speed = -0.1)).validate() }
    }

    @Test
    fun `速度超上限被拒绝`() {
        assertFails { recipe(SubEmit(speed = SubEmit.MAX_SPEED + 0.1)).validate() }
    }

    @Test
    fun `尺寸比例过小被拒绝`() {
        assertFails { recipe(SubEmit(scale = 0.0)).validate() }
    }
}