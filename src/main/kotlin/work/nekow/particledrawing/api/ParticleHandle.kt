package work.nekow.particledrawing.api

import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import work.nekow.particledrawing.core.easing.EasingType
import work.nekow.particledrawing.core.server.AnimationScheduler
import work.nekow.particledrawing.core.server.ParticleData
import work.nekow.particledrawing.util.AttachMath
import java.util.UUID

/**
 * 已生成粒子的句柄，支持属性更新和生命周期控制。
 * 通过 [ParticleManager.create] 创建。
 */
@Suppress("unused")
class ParticleHandle(
    val id: UUID,
    private val manager: ParticleManager
) {

    // 延迟生成的到点 tick（Builder.delay）：到点前这颗粒子在 PD 侧还不存在
    @Volatile
    private var pendingDueTick: Long = 0

    /**
     * 是否处在「已登记延迟、还没真正生成」的窗口里。
     * [ParticleBatch] 靠它别把延迟成员当已过期摘掉；到点之后无论生成成功与否都按普通成员处理。
     */
    fun isPending(): Boolean = pendingDueTick > 0 && AnimationScheduler.currentTick() < pendingDueTick

    internal fun markPending(dueTick: Long) {
        pendingDueTick = dueTick
    }

    /**
     * 使用缓动将粒子移动到新位置。
     * @param target 目标位置
     * @param durationTicks 持续 tick 数
     * @param easing 缓动类型
     * @return 自身，支持链式调用
     */
    fun move(target: Vec3, durationTicks: Int, easing: EasingType): ParticleHandle {
        val engine = manager.getEngine()
        val data = engine.getParticle(id) ?: return this

        engine.updateParticle(
            id, target, data.color(), data.scale(),
            updatePos = true, updateColor = false, updateScale = false,
            durationTicks, easing, manager.getPlayers()
        )
        return this
    }

    /** [move] 的分量重载。 */
    fun move(x: Number, y: Number, z: Number, durationTicks: Int, easing: EasingType): ParticleHandle {
        return move(Vec3(x.toDouble(), y.toDouble(), z.toDouble()), durationTicks, easing)
    }

    /**
     * 立即移动粒子（无缓动）。
     * @param target 目标位置
     * @return 自身，支持链式调用
     */
    fun moveInstant(target: Vec3): ParticleHandle {
        return move(target, 0, EasingType.LINEAR)
    }

    /** [moveInstant] 的分量重载。 */
    fun moveInstant(x: Number, y: Number, z: Number): ParticleHandle {
        return moveInstant(Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
    }

    /**
     * 设置粒子的速度向量（blocks/tick）：速度驱动接管位置（解除实体锚点），之后两端按同一规则
     * 逐 tick 积分（位置 += 速度），不必每 tick 报位置。
     * @param velocity 速度向量
     * @return 自身，支持链式调用
     */
    fun setVelocity(velocity: Vec3): ParticleHandle {
        manager.getEngine().setVelocity(id, velocity, manager.getPlayers())
        return this
    }

    /** [setVelocity] 的分量重载。 */
    fun setVelocity(x: Number, y: Number, z: Number): ParticleHandle {
        return setVelocity(Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
    }

    /**
     * 直设粒子位置（无缓动、无跳变）：客户端用 partialTick 在上一位置与本位置之间插值。
     * 供「每 tick 跟随一个非实体点」的粒子（如投射物本体）使用——位置精确且渲染丝滑，
     * 不会像 [move] 缓动那样永远比真实位置慢一拍。
     * @param target 目标位置
     * @return 自身，支持链式调用
     */
    fun track(target: Vec3): ParticleHandle {
        manager.getEngine().trackParticle(id, target, manager.getPlayers())
        return this
    }

    /** [track] 的分量重载。 */
    fun track(x: Number, y: Number, z: Number): ParticleHandle {
        return track(Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
    }

    /**
     * 获取粒子当前在服务端的速度向量。
     * @return 速度向量，不存在则返回 null
     */
    fun velocity(): Vec3? {
        return manager.getEngine().getParticle(id)?.velocity()
    }

    /**
     * 获取粒子当前在服务端的权威位置。
     * @return 位置，不存在则返回 null
     */
    fun position(): Vec3? {
        return manager.getEngine().getParticle(id)?.position()
    }

    /** 集合簿记用：一次取到权威位置与速度；粒子已不存在时返回 null（不对外暴露 core 类型）。 */
    internal fun state(): Pair<Vec3, Vec3>? {
        val data = manager.getEngine().getParticle(id) ?: return null
        return data.position() to data.velocity()
    }

    /**
     * 给粒子施加加速度（服务端权威力）：只在开始施力时下发一次，之后服务端与客户端按同一
     * 规则逐 tick 积分（速度 += 加速度，位置 += 速度）。让大量粒子沿同一个力运动时，
     * 不必每 tick 逐粒子广播速度或位置。
     *
     * 与 [setVelocity] 叠加（力不覆盖已有速度）；位置指令（[move]/[track]）会停掉速度与力。
     * 清除力用 `applyForce(Vec3.ZERO, 0)`，连速度一起停用 `setVelocity(Vec3.ZERO)`。
     *
     * 一整组粒子每颗各自的力（每 tick 按距离重算）用 [ParticleBatch.applyForceAll]：一次包覆盖全组。
     *
     * @param acceleration 加速度（blocks/tick²）
     * @param ticks 施力 tick 数：>0 = 施加这么多 tick；<0 = 无限（默认，直到被下一次指令覆盖）；0 = 清除力
     * @return 自身，支持链式调用
     */
    fun applyForce(acceleration: Vec3, ticks: Int = -1): ParticleHandle {
        manager.getEngine().applyForce(id, acceleration, ticks, manager.getPlayers())
        return this
    }

    /** [applyForce] 的分量重载。 */
    fun applyForce(ax: Number, ay: Number, az: Number, ticks: Int = -1): ParticleHandle {
        return applyForce(Vec3(ax.toDouble(), ay.toDouble(), az.toDouble()), ticks)
    }

    /**
     * 把粒子钉在实体上：位置 = 实体位置 + [offset]（世界空间），客户端每 tick 本地解析，
     * 服务端只在挂载时下发一次——实体怎么动粒子就怎么动，没有逐 tick 的带宽开销。
     *
     * **身份**：[Entity] / `uuid` / `entityId` 三个入口是同一实现。服务端把 UUID 当主身份、
     * 网络 id 当解析缓存（网络 id 会随实体重载/换维度变化，UUID 不会），客户端解析时也优先用 UUID。
     *
     * **生命周期**：实体不在场（未加载 / 已消失 / 换到别的维度）时粒子保持最后一次位置，
     * 实体再出现就继续跟随；锚点不会替粒子决定寿命——粒子自身的 `lifetime` 与 [remove] 照常生效，
     * 实体死亡也一样（要跟着销毁就自己调 [remove]）。位置与运动指令
     * （[move]/[track]/[setVelocity]/[applyForce]）会解除锚点。
     *
     * @param entity 目标实体
     * @param offset 相对实体位置的偏移（世界空间，基准是实体脚底 `Entity.position()`）
     * @return 自身，支持链式调用
     */
    fun attachTo(entity: Entity, offset: Vec3): ParticleHandle {
        return attachTo(entity.id, entity.uuid, offset, false)
    }

    /** [attachTo] 的重载：按实体网络 id（拿不到 UUID 时用；进客户端后不再复核身份）。 */
    fun attachTo(entityId: Int, offset: Vec3): ParticleHandle {
        return attachTo(entityId, null, offset, false)
    }

    /** [attachTo] 的重载：按实体 UUID（服务端当拍解析网络 id，解析不到则每 tick 重试）。 */
    fun attachTo(uuid: UUID, offset: Vec3): ParticleHandle {
        val entity = manager.level.getEntity(uuid)
        return attachTo(entity?.id ?: AttachMath.noEntity(), uuid, offset, false)
    }

    /** [attachTo] 的分量重载。 */
    fun attachTo(entity: Entity, x: Number, y: Number, z: Number): ParticleHandle {
        return attachTo(entity, Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
    }

    /** [attachTo] 的分量重载。 */
    fun attachTo(entityId: Int, x: Number, y: Number, z: Number): ParticleHandle {
        return attachTo(entityId, Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
    }

    /** [attachTo] 的分量重载。 */
    fun attachTo(uuid: UUID, x: Number, y: Number, z: Number): ParticleHandle {
        return attachTo(uuid, Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
    }

    /**
     * 把粒子钉在实体上，偏移随实体朝向旋转（实体局部空间）。
     * 与 [attachTo] 只差 [offset] 的坐标系：需要「贴在身前/身侧」这类随转向变化的位置时用它。
     *
     * @param entity 目标实体
     * @param offset 实体局部空间中的偏移
     * @return 自身，支持链式调用
     */
    fun attachToLocal(entity: Entity, offset: Vec3): ParticleHandle {
        return attachTo(entity.id, entity.uuid, offset, true)
    }

    /** [attachToLocal] 的重载：按实体网络 id。 */
    fun attachToLocal(entityId: Int, offset: Vec3): ParticleHandle {
        return attachTo(entityId, null, offset, true)
    }

    /** [attachToLocal] 的重载：按实体 UUID。 */
    fun attachToLocal(uuid: UUID, offset: Vec3): ParticleHandle {
        val entity = manager.level.getEntity(uuid)
        return attachTo(entity?.id ?: AttachMath.noEntity(), uuid, offset, true)
    }

    /** [attachToLocal] 的分量重载。 */
    fun attachToLocal(entity: Entity, x: Number, y: Number, z: Number): ParticleHandle {
        return attachToLocal(entity, Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
    }

    /** [attachToLocal] 的分量重载。 */
    fun attachToLocal(entityId: Int, x: Number, y: Number, z: Number): ParticleHandle {
        return attachToLocal(entityId, Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
    }

    /** [attachToLocal] 的分量重载。 */
    fun attachToLocal(uuid: UUID, x: Number, y: Number, z: Number): ParticleHandle {
        return attachToLocal(uuid, Vec3(x.toDouble(), y.toDouble(), z.toDouble()))
    }

    /** 锚点的统一实现：uuid 为主身份，[entityId] 只是当拍解析缓存（未解析到给 -1）。 */
    private fun attachTo(entityId: Int, uuid: UUID?, offset: Vec3, local: Boolean): ParticleHandle {
        manager.getEngine().attachParticle(id, entityId, uuid, offset, local, manager.getPlayers())
        return this
    }

    /**
     * 动态修改粒子的发光光照等级 (0-15)，并同步到客户端。
     * @param level 目标光照等级，自动钳制到 [0, 15]
     * @return 自身，支持链式调用
     */
    fun lightLevel(level: Int): ParticleHandle {
        manager.getEngine().setLightLevel(id, level, manager.getPlayers())
        return this
    }

    /**
     * 使用缓动改变粒子颜色。
     * @param color 目标颜色
     * @param durationTicks 持续 tick 数
     * @param easing 缓动类型
     * @return 自身，支持链式调用
     */
    fun recolor(color: Color, durationTicks: Int, easing: EasingType): ParticleHandle {
        val engine = manager.getEngine()
        val data = engine.getParticle(id) ?: return this

        engine.updateParticle(
            id, data.position(), color, data.scale(),
            updatePos = false, updateColor = true, updateScale = false,
            durationTicks, easing, manager.getPlayers()
        )
        return this
    }

    /**
     * 使用缓动改变粒子缩放。
     * @param scale 目标缩放值
     * @param durationTicks 持续 tick 数
     * @param easing 缓动类型
     * @return 自身，支持链式调用
     */
    fun resize(scale: Float, durationTicks: Int, easing: EasingType): ParticleHandle {
        val engine = manager.getEngine()
        val data = engine.getParticle(id) ?: return this

        engine.updateParticle(
            id, data.position(), data.color(), scale,
            updatePos = false, updateColor = false, updateScale = true,
            durationTicks, easing, manager.getPlayers()
        )
        return this
    }

    /**
     * 立即销毁此粒子。
     */
    fun remove() {
        manager.getEngine().destroyParticle(id, manager.getPlayers())
    }

    /**
     * 获取粒子当前在服务端的状态。
     * @return 粒子数据，不存在则返回 null
     */
    fun data(): ParticleData? {
        return manager.getEngine().getParticle(id)
    }

    /**
     * 用于通过流式 API 创建粒子的构建器。
     *
     * 外观（贴图 / UV / 各向异性尺寸 / 朝向 / 加色）一律**生成时定死**：PD 只在生成那一次把它同步给
     * 客户端，不产生任何逐 tick 开销，之后也改不了。
     */
    @Suppress("unused")
    class Builder(private val manager: ParticleManager) {

        private var position: Vec3 = Vec3.ZERO
        private var color: Color = Color.WHITE
        private var scale: Float = 1.0f
        private var lifetime: Int = -1
        private var groupId: UUID? = null
        private var glowing: Boolean = false
        private var lightLevel: Int = 15
        private var offsetFromPivot: Vec3 = Vec3.ZERO
        private var delayTicks: Int = 0

        // 外观规格按需创建：不碰外观的粒子（绝大多数）一个对象都不多分配
        private var visualSpec: ParticleVisual? = null

        // 寿命曲线与首帧插值端点（同样按需创建）
        private var lifeCurve: ParticleLifeCurve? = null
        private var prevPosition: Vec3? = null
        private var fadeOutTicks: Int = 0
        private var fadeOutEasing: EasingType = EasingType.EASE_IN
        private var shrinkTarget: Float = 1f
        private var shrinkTicks: Int = 0
        private var shrinkEasing: EasingType = EasingType.EASE_IN

        private fun spec(): ParticleVisual = visualSpec ?: ParticleVisual().also { visualSpec = it }

        /** 设置粒子位置。 */
        fun position(pos: Vec3) = apply { this.position = pos }

        /** 设置粒子位置。 */
        fun position(x: Number, y: Number, z: Number) = apply {
            this.position = Vec3(x.toDouble(), y.toDouble(), z.toDouble())
        }

        /** 设置粒子颜色。 */
        fun color(color: Color) = apply { this.color = color }

        /** 设置粒子颜色（整数分量）。 */
        fun color(r: Int, g: Int, b: Int) = apply {
            this.color = Color.ofInt(r, g, b)
        }

        /** 设置粒子颜色（整数分量，含透明度）。 */
        fun color(r: Int, g: Int, b: Int, a: Int) = apply {
            this.color = Color.ofInt(r, g, b, a)
        }

        /** 设置粒子缩放（各向同性，编辑器单位：渲染整宽 = 值 × 0.2 格 × 贴图尺寸系数）。 */
        fun scale(scale: Float) = apply { this.scale = scale }

        /**
         * 设置各向异性缩放（编辑器单位）：长轴 [w]（四边形局部 X）、短轴 [h]（局部 Y）。
         * 任何一轴给 0 表示那一轴沿用 [scale]。要按世界格给尺寸用 [scaleWorld]。
         */
        fun scale(w: Float, h: Float) = apply { spec().aniso(w, h) }

        /** 设置各向异性缩放（**世界格整宽/整高**）：与贴图尺寸无关，`Draw.polyline` 这类几何用它。 */
        fun scaleWorld(w: Float, h: Float) = apply { spec().anisoWorld(w, h) }

        /**
         * 设置粒子生命周期（单位 tick）。-1 表示永存。
         * @param ticks 生命周期 tick 数
         */
        fun lifetime(ticks: Int) = apply { this.lifetime = ticks }

        /** 关联到指定粒子组。 */
        fun group(groupId: UUID) = apply { this.groupId = groupId }

        /** 标记为发光粒子。 */
        fun glowing(glowing: Boolean) = apply { this.glowing = glowing }

        /**
         * 设置发光粒子向外发出的光照等级 (0-15)，仅当 [glowing] 为 true 时生效。
         * @param level 光照等级，自动钳制到 [0, 15]
         */
        fun lightLevel(level: Int) = apply { this.lightLevel = level.coerceIn(0, 15) }

        /** 设置相对组轴心的偏移。 */
        fun offsetFromPivot(offset: Vec3) = apply { this.offsetFromPivot = offset }

        /** 设置相对组轴心的偏移。 */
        fun offsetFromPivot(x: Number, y: Number, z: Number) = apply {
            this.offsetFromPivot = Vec3(x.toDouble(), y.toDouble(), z.toDouble())
        }

        /**
         * 用整张贴图（名字先经 [ParticleManager.registerTexture] 或 [ParticleStyle] 登记）。
         * 没登记过的名字不会丢粒子，只是渲染成纯白方块。
         */
        fun texture(name: String) = apply { spec().texture(name) }

        /** 用内置形状（[ParticleStyle.SQUARE] 等于清掉贴图）。 */
        fun style(style: ParticleStyle) = apply { spec().style(style) }

        /** 取贴图的子矩形 UV（贴图像素，顺序 u0, v0, u1, v1）；取样框同时决定渲染尺寸系数。 */
        fun uv(u0: Float, v0: Float, u1: Float, v1: Float) = apply { spec().uv(u0, v0, u1, v1) }

        /** 广告牌开关：false = 朝向固定（世界 +Z 起算），此时 [spin] / [alignTo] 才生效。 */
        fun billboard(enabled: Boolean) = apply { spec().billboard(enabled) }

        /** 绕四边形法线（局部 Z）转 [radians] 弧度；[spinLocal] = false 时按世界轴外旋。 */
        @JvmOverloads
        fun spin(radians: Double, spinLocal: Boolean = true) = apply { spec().spin(radians, spinLocal) }

        /** 三轴自转（弧度）。 */
        @JvmOverloads
        fun spin(radiansX: Double, radiansY: Double, radiansZ: Double, spinLocal: Boolean = true) =
            apply { spec().spin(radiansX, radiansY, radiansZ, spinLocal) }

        /**
         * 把长轴对齐到 `to - from` 方向（一条丝沿线段躺好）；会一并关掉广告牌。
         * 配合 [scale] 的两参版本 / [scaleWorld] 用。
         */
        fun alignTo(from: Vec3, to: Vec3) = apply { spec().alignTo(from, to) }

        /** 加法混合开关：亮部叠亮、有溢出感（参考图那种「光溢出来」靠它）。 */
        fun additive(enabled: Boolean) = apply { spec().additive(enabled) }

        /** 直接给一整份外观规格（会拷贝一份，之后改原对象不影响本颗粒子）。 */
        fun visual(visual: ParticleVisual) = apply { this.visualSpec = visual.copy() }

        /**
         * 逐粒子生成延迟（tick）：生成请求推迟这么多 tick 才真正发出去。
         *
         * 用来「一条指令铺出分批出现」的粒子；与 [Draw] 的 `stagger` 是同一套调度器，
         * 区别是这里按颗调度、[Draw] 按图元序号调度。
         *
         * 注意：延迟期间这颗粒子在 PD 侧还不存在——[spawn] 返回的句柄上的操作全部无效，
         * 也**不要**在这之前把它登记进 [ParticleBatch]（成员会被当成已死而摘掉）。
         * 延迟生成没有「上一 tick」可言，所以与 [prevPosition] 同用时后者不生效。
         */
        fun delay(ticks: Int) = apply { this.delayTicks = ticks }

        /**
         * 首帧插值端点：客户端渲染的第一帧从 [prev] 扫到 [position]（与 `track` 的段同语义，
         * 原版按 partialTick 在两点之间插值）。
         *
         * 用于「服务端每 tick 采样、逐颗铺拖尾」：把上一 tick 的位置一起给出，尾巴就不会
         * 比头部超前或落后一整 tick（不给的话粒子直接钉在本 tick 位置，高刷下能看出一个 tick 的错位）。
         */
        fun prevPosition(prev: Vec3) = apply { this.prevPosition = prev }

        /** [prevPosition] 的分量重载。 */
        fun prevPosition(x: Number, y: Number, z: Number) = apply {
            this.prevPosition = Vec3(x.toDouble(), y.toDouble(), z.toDouble())
        }

        /**
         * 给某个通道逐帧的外观乘数（寿命曲线）：通道值缺省 1.0，之后逐帧乘在颜色/缩放上。
         * 时刻从**生成那一刻**算起（tick），段内用后一关键帧的缓动。
         */
        fun curve(channel: CurveChannel, keys: List<CurveKey>) = apply {
            lifeCurve = (lifeCurve ?: ParticleLifeCurve.EMPTY).plus(ParticleCurve(channel, keys))
        }

        /** 直接给一条曲线。 */
        fun curve(curve: ParticleCurve) = apply {
            lifeCurve = (lifeCurve ?: ParticleLifeCurve.EMPTY).plus(curve)
        }

        /** 透明度曲线（乘数）。 */
        fun alphaCurve(vararg keys: CurveKey) = curve(ParticleCurve.alpha(*keys))

        /** 尺寸曲线（乘数；>1 变大、<1 缩小）。 */
        fun sizeCurve(vararg keys: CurveKey) = curve(ParticleCurve.scale(*keys))

        /** RGB 三条颜色曲线（乘数，逐通道给关键帧）。 */
        fun colorCurve(red: List<CurveKey>, green: List<CurveKey>, blue: List<CurveKey>) = apply {
            curve(ParticleCurve(CurveChannel.RED, red))
            curve(ParticleCurve(CurveChannel.GREEN, green))
            curve(ParticleCurve(CurveChannel.BLUE, blue))
        }

        /** 直接给整套寿命曲线（覆盖之前设的）。 */
        fun lifeCurve(curve: ParticleLifeCurve) = apply { this.lifeCurve = curve }

        /**
         * 寿命最后 [ticks] tick 内淡出到透明（需要有限寿命，见 [lifetime]）。
         * 密集拖尾用它代替「额外两个更新包」的 recolor，零逐帧带宽。
         */
        fun fadeOut(ticks: Int, easing: EasingType = EasingType.EASE_IN) = apply {
            fadeOutTicks = ticks
            fadeOutEasing = easing
        }

        /** 寿命最后 [ticks] tick 内缩到 [factor] 倍（需要有限寿命）。 */
        fun shrinkTo(factor: Float, ticks: Int, easing: EasingType = EasingType.EASE_IN) = apply {
            shrinkTarget = factor
            shrinkTicks = ticks
            shrinkEasing = easing
        }

        /** 把 [fadeOut] / [shrinkTo] 的糖按当前寿命展开成关键帧。 */
        private fun resolvedLifeCurve(): ParticleLifeCurve? {
            if (fadeOutTicks <= 0 && shrinkTicks <= 0) return lifeCurve
            var out = lifeCurve ?: ParticleLifeCurve.EMPTY
            if (fadeOutTicks > 0) {
                out = out.plus(LifeCurveSugar.fadeOut(lifetime, fadeOutTicks, fadeOutEasing))
            }
            if (shrinkTicks > 0) {
                out = out.plus(LifeCurveSugar.shrinkTo(lifetime, shrinkTarget, shrinkTicks, shrinkEasing))
            }
            return out
        }

        /**
         * 生成粒子并返回句柄以供后续控制。
         *
         * 带 [delay] 时只登记延迟任务，粒子的实际生成（含维度上限检查）在到点那一刻发生。
         *
         * @return 生成粒子的句柄；因达到维度粒子上限被拒绝时为 null
         */
        fun spawn(): ParticleHandle? {
            val engine = manager.getEngine()
            val id = UUID.randomUUID()
            val players = manager.getPlayers()
            val spec = visualSpec
            // fadeOut/shrinkTo 要靠寿命算关键帧：在这里（寿命已定）展开，无限寿命会在这里明确报错
            val curve = resolvedLifeCurve()

            if (delayTicks > 0) {
                val due = AnimationScheduler.currentTick() + delayTicks.coerceAtLeast(1)
                AnimationScheduler.schedule(delayTicks) {
                    engine.spawnParticle(id, position, color, scale, lifetime,
                        groupId, glowing, lightLevel, offsetFromPivot, players, spec, curve, null)
                }
                return ParticleHandle(id, manager).also { it.markPending(due) }
            }

            engine.spawnParticle(id, position, color, scale, lifetime,
                groupId, glowing, lightLevel, offsetFromPivot, players, spec, curve, prevPosition) ?: return null
            return ParticleHandle(id, manager)
        }
    }
}
