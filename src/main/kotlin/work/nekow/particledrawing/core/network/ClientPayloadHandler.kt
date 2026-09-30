package work.nekow.particledrawing.core.network

import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.network.handling.IPayloadContext
import work.nekow.particledrawing.api.ParticleVisual
import work.nekow.particledrawing.core.client.ClientAnimationManager
import work.nekow.particledrawing.core.client.ClientAnimationSyncManager
import work.nekow.particledrawing.core.client.ClientParticleEngine
import work.nekow.particledrawing.core.client.ClientTextureSyncManager
import work.nekow.particledrawing.core.client.TextureCache

/**
 * 客户端数据包处理器，将各类数据包分发到 [ClientParticleEngine] 的对应方法。
 */
internal object ClientPayloadHandler {

    fun handleSpawn(payload: ParticleSpawnPayload, context: IPayloadContext) {
        context.enqueueWork {
            val visual = payload.visual
            // 贴图 + 子矩形 → 渲染用 UV（贴图没到货时按名回落，UV 尺寸仍按请求的取景框算）
            val entry = visual?.texture?.let { TextureCache.get(it) }
            val uv = visual?.toUvData(entry?.width ?: 0, entry?.height ?: 0)
            val spin = if (visual != null && !visual.billboard) {
                doubleArrayOf(visual.spinXDeg, visual.spinYDeg, visual.spinZDeg)
            } else {
                ClientParticleEngine.ZERO_SPIN
            }
            ClientParticleEngine.instance()?.spawnParticle(
                payload.particleId,
                payload.x, payload.y, payload.z,
                payload.r, payload.g, payload.b, payload.a,
                payload.scale, payload.lifetime,
                payload.groupId, payload.glowing, payload.lightLevel,
                uv,
                visual?.billboard ?: true,
                spin,
                visual?.spinLocal ?: true,
                visual?.additive ?: false,
                visual?.resolvedAniso(payload.scale, ParticleVisual.texScale(uv)),
            )
        }
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

    // —— 编排动画程序（客户端自驱） ——

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

    // —— 特效 API（按 key 播放 + 锚点 + 时钟 + 资源下发） ——

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

    // —— 动画文件同步（配置阶段） ——

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
