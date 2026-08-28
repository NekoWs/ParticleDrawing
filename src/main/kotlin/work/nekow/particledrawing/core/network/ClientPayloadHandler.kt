package work.nekow.particledrawing.core.network

import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext
import work.nekow.particledrawing.core.client.ClientAnimationManager
import work.nekow.particledrawing.core.client.ClientAnimationSyncManager
import work.nekow.particledrawing.core.client.ClientParticleEngine

/**
 * 客户端数据包处理器，将各类数据包分发到 [ClientParticleEngine] 的对应方法。
 */
internal object ClientPayloadHandler {

    fun handleSpawn(payload: ParticleSpawnPayload, context: IPayloadContext) {
        context.enqueueWork {
            ClientParticleEngine.instance()?.spawnParticle(
                payload.particleId,
                payload.x, payload.y, payload.z,
                payload.r, payload.g, payload.b, payload.a,
                payload.scale, payload.lifetime,
                payload.groupId, payload.glowing, payload.lightLevel
            )
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

    // ---- 编排动画程序（客户端自驱） ----

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
                Vec3(payload.originX, payload.originY, payload.originZ)
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

    // ---- 动画文件同步（配置阶段） ----

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
