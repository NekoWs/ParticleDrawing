package work.nekow.particledrawing.core.network

import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext
import work.nekow.particledrawing.core.client.ClientAnimationManager
import work.nekow.particledrawing.core.client.ClientAnimationSyncManager
import work.nekow.particledrawing.core.client.ClientEmitterManager
import work.nekow.particledrawing.core.client.ClientParticleEngine
import work.nekow.particledrawing.core.client.ClientTextureSyncManager
import work.nekow.particledrawing.core.client.ResolvedVisual

/**
 * 客户端数据包处理器，把各类数据包分发到对应的客户端管理器。
 */
internal object ClientPayloadHandler {

    fun handleSpawn(payload: ParticleSpawnPayload, context: IPayloadContext) {
        context.enqueueWork {
            val engine = ClientParticleEngine.instance() ?: return@enqueueWork
            spawnOne(engine, payload)
        }
    }

    /** 批量生成：整批走与单发完全相同的落地路径。 */
    fun handleSpawnBatch(payload: ParticleSpawnBatchPayload, context: IPayloadContext) {
        context.enqueueWork {
            val engine = ClientParticleEngine.instance() ?: return@enqueueWork
            for (entry in payload.entries) spawnOne(engine, entry)
        }
    }

    private fun spawnOne(engine: ClientParticleEngine, p: ParticleSpawnPayload) {
        engine.spawnParticle(
            p.particleId,
            p.x, p.y, p.z,
            p.r, p.g, p.b, p.a,
            p.scale, p.lifetime,
            p.groupId, p.glowing, p.lightLevel,
            ResolvedVisual.of(p.visual, p.scale),
            p.lifeCurve,
            p.prev,
        )
    }

    /** 程序化贴图内容块：按 id 累积，收齐后解码注册。 */
    fun handleTexture(payload: ParticleTexturePayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientTextureSyncManager.onChunk(payload)
        }
    }

    fun handleUpdate(payload: ParticleUpdatePayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientParticleEngine.instance()?.updateParticle(
                payload.particleId,
                payload.x, payload.y, payload.z,
                payload.r, payload.g, payload.b, payload.a,
                payload.scale,
                payload.hasPosition, payload.hasColor, payload.hasScale,
                payload.durationTicks, payload.easingType()
            )
        }
    }

    fun handleDestroy(payload: ParticleDestroyPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientParticleEngine.instance()?.destroyParticles(payload.particleIds)
        }
    }

    fun handleVelocity(payload: ParticleVelocityPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientParticleEngine.instance()?.setVelocity(
                payload.particleId, payload.vx, payload.vy, payload.vz
            )
        }
    }

    fun handleVelocityBatch(payload: ParticleVelocityBatchPayload, context: IPayloadContext) {
        context.enqueueWork {
            val engine = ClientParticleEngine.instance() ?: return@enqueueWork
            for (u in payload.updates) {
                engine.setVelocity(u.particleId, u.vx, u.vy, u.vz)
            }
        }
    }

    fun handleForce(payload: ParticleForcePayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientParticleEngine.instance()?.setAcceleration(
                payload.particleId, payload.ax, payload.ay, payload.az, payload.ticks
            )
        }
    }

    fun handleForceBatch(payload: ParticleForceBatchPayload, context: IPayloadContext) {
        context.enqueueWork {
            val engine = ClientParticleEngine.instance() ?: return@enqueueWork
            for (u in payload.updates) {
                engine.setAcceleration(u.particleId, u.ax, u.ay, u.az, payload.ticks)
            }
        }
    }

    fun handleTrack(payload: ParticleTrackPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientParticleEngine.instance()?.trackParticle(
                payload.particleId, payload.x, payload.y, payload.z
            )
        }
    }

    fun handleTrackBatch(payload: ParticleTrackBatchPayload, context: IPayloadContext) {
        context.enqueueWork {
            val engine = ClientParticleEngine.instance() ?: return@enqueueWork
            for (u in payload.updates) {
                engine.trackParticle(u.particleId, u.x, u.y, u.z)
            }
        }
    }

    fun handleAttach(payload: ParticleAttachPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientParticleEngine.instance()?.attachParticle(
                payload.particleId, payload.entityId, payload.entityUuid,
                payload.ox, payload.oy, payload.oz, payload.local
            )
        }
    }

    fun handleRotation(payload: ParticleRotationPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientParticleEngine.instance()?.rotateParticle(
                payload.particleId,
                payload.px, payload.py, payload.pz,
                payload.ox, payload.oy, payload.oz,
                payload.rx, payload.ry, payload.rz,
                payload.durationTicks, payload.easingType()
            )
        }
    }

    fun handleTranslate(payload: ParticleTranslatePayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientParticleEngine.instance()?.translateParticle(
                payload.particleId,
                payload.px, payload.py, payload.pz,
                payload.ox, payload.oy, payload.oz,
                payload.tx, payload.ty, payload.tz,
                payload.durationTicks, payload.easingType()
            )
        }
    }

    fun handleSetPosition(payload: ParticleSetPositionPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientParticleEngine.instance()?.setPosition(
                payload.particleId,
                payload.px, payload.py, payload.pz,
                payload.ox, payload.oy, payload.oz,
                payload.durationTicks, payload.easingType()
            )
        }
    }

    fun handleLightLevel(payload: ParticleLightLevelPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientParticleEngine.instance()?.setLightLevel(
                payload.particleId, payload.lightLevel
            )
        }
    }

    // 运行时发射器：客户端按渲染帧自己发射

    fun handleEmitterSpawn(payload: EmitterSpawnPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientEmitterManager.spawn(payload)
        }
    }

    fun handleEmitterUpdate(payload: EmitterUpdatePayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientEmitterManager.update(payload)
        }
    }

    fun handleEmitterStop(payload: EmitterStopPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientEmitterManager.stop(payload.emitterId)
        }
    }

    // 编排动画程序：客户端自驱

    fun handleProgram(payload: AnimationProgramPayload, context: IPayloadContext) {
        context.enqueueWork {
            work.nekow.particledrawing.core.client.ClientAnimationProgramManager.arm(
                payload.programId, payload.particleIds, payload.anchorGameTime,
                payload.initialPivot, payload.entityBindings, payload.vars, payload.instructions,
            )
        }
    }

    fun handleProgramAppend(payload: AnimationProgramAppendPayload, context: IPayloadContext) {
        context.enqueueWork {
            work.nekow.particledrawing.core.client.ClientAnimationProgramManager.append(
                payload.programId, payload.instructions
            )
        }
    }

    fun handleSetProgramVar(payload: SetProgramVarPayload, context: IPayloadContext) {
        context.enqueueWork {
            work.nekow.particledrawing.core.client.ClientAnimationProgramManager.setVariable(
                payload.programId, payload.name, payload.value
            )
        }
    }

    /** 变量热更并渐变到目标值。 */
    fun handleSetProgramVarEase(payload: SetProgramVarEasePayload, context: IPayloadContext) {
        context.enqueueWork {
            work.nekow.particledrawing.core.client.ClientAnimationProgramManager.setVariableEased(
                payload.programId, payload.name, payload.value, payload.durationMs, payload.easing
            )
        }
    }

    /** 移动轴心的相邻样本（上一位置 → 当前位置）。 */
    fun handleProgramAnchor(payload: ProgramAnchorPayload, context: IPayloadContext) {
        context.enqueueWork {
            work.nekow.particledrawing.core.client.ClientAnimationProgramManager.applyAnchor(
                payload.programId,
                Vec3(payload.prevX, payload.prevY, payload.prevZ),
                Vec3(payload.x, payload.y, payload.z),
                Vec3(payload.vx, payload.vy, payload.vz),
            )
        }
    }

    fun handleStopProgram(payload: StopAnimationProgramPayload, context: IPayloadContext) {
        context.enqueueWork {
            work.nekow.particledrawing.core.client.ClientAnimationProgramManager.stop(
                payload.programId, payload.destroyParticles
            )
        }
    }

    fun handlePlayAnimation(payload: PlayAnimationPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientAnimationManager.play(
                payload.animationId,
                payload.data,
                Vec3(payload.originX, payload.originY, payload.originZ),
                payload.startGameTick
            )
        }
    }

    fun handlePlayAnimationData(payload: PlayAnimationDataPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientAnimationManager.play(
                payload.animationId,
                payload.animation,
                Vec3(payload.originX, payload.originY, payload.originZ),
                payload.startGameTick
            )
        }
    }

    fun handleVariableUpdate(payload: VariableUpdatePayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientAnimationManager.updateVariable(payload.animationId, payload.variable, payload.value)
        }
    }

    fun handleStopAnimation(payload: StopAnimationPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientAnimationManager.stop(payload.animationId)
        }
    }

    // 特效 API：按 key 播放 + 锚点 + 时钟 + 资源下发

    fun handlePlayEffect(payload: PlayEffectPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientAnimationManager.playEffect(payload)
        }
    }

    fun handleAnchorUpdates(payload: AnchorUpdateBatchPayload, context: IPayloadContext) {
        context.enqueueWork {
            for (u in payload.updates) {
                ClientAnimationManager.updateAnchor(
                    u.playbackId,
                    Vec3(u.x, u.y, u.z),
                    Vec3(u.vx, u.vy, u.vz),
                )
            }
        }
    }

    fun handleClockSync(payload: ClockSyncPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientAnimationManager.applyClockSync(payload.playbackId, payload.position, payload.playing, payload.speed)
        }
    }

    fun handleEffectData(payload: EffectDataPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientAnimationManager.onEffectData(payload.key, payload.data)
        }
    }

    // 动画文件同步（配置阶段）

    fun handleSyncBegin(payload: AnimationSyncBeginPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientAnimationSyncManager.onBegin(context)
        }
    }

    fun handleSyncFile(payload: AnimationSyncFilePayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientAnimationSyncManager.onFileChunk(payload.name, payload.eof, payload.data)
        }
    }

    fun handleSyncDone(payload: AnimationSyncDonePayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientAnimationSyncManager.onDone(context)
        }
    }
}
