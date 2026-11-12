# 脚底光环 + 从环里升起的竖条柱。
# 竖条用 end_rod 贴图（细长精灵）；发射器靠 orbit 在 4.5 格内循环爬升，
# 粒子留在出生高度，柱子就是它走过的记录。锚点换成 entity 即可跟人走。
pdraw particle emitter reset halo
pdraw particle emitter halo anchor fixed ~ ~0.06 ~
pdraw particle emitter halo sample range -3.1416 3.1416 0.15708
pdraw particle emitter halo birth position vec3(cos(u)*1.25, 0, sin(u)*1.25)
pdraw particle emitter halo birth r 1
pdraw particle emitter halo birth g 0.95
pdraw particle emitter halo birth b 0.45
pdraw particle emitter halo appearance size_world 0.16,0.16
pdraw particle emitter halo appearance glowing true
pdraw particle emitter halo lifetime -1
pdraw particle emitter halo max_alive 41
pdraw particle emitter halo rate 41
pdraw particle emitter halo burst

pdraw particle emitter reset aura
pdraw particle emitter aura anchor fixed ~ ~0.06 ~
pdraw particle emitter aura sample range -3.1416 3.1416 0.31416
pdraw particle emitter aura birth position vec3(cos(u)*1.2, 0, sin(u)*1.2)
pdraw particle emitter aura birth r 1
pdraw particle emitter aura birth g 0.78
pdraw particle emitter aura birth b 0.15
pdraw particle emitter aura texture particle:minecraft:end_rod
pdraw particle emitter aura appearance size_world 0.09,0.32
pdraw particle emitter aura appearance glowing true
pdraw particle emitter aura orbit vec3(0, fract(t*0.00035)*4.5, 0)
pdraw particle emitter aura lifetime 115
pdraw particle emitter aura rate 10
pdraw particle emitter aura interval 2
pdraw particle emitter aura max_alive 600
pdraw particle emitter aura start