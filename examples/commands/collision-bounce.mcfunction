# 粒子落地反弹：从上方撒下带初速的粒子，撞到地面后按 bounce/friction 弹跳、减速、停下。
# 公式拿不到方块信息，碰撞是单独的配置项，默认关闭；打开后由客户端按碰撞箱判定。
# 想让它们更「弹」就调大 bounce，想更快停住就调小 friction。
pdraw particle emitter reset sparks
pdraw particle emitter sparks anchor fixed ~ ~6 ~
pdraw particle emitter sparks sample count 1
pdraw particle emitter sparks birth position vec3((rand()-0.5)*6, 0, (rand()-0.5)*6)
pdraw particle emitter sparks birth vy -0.55
pdraw particle emitter sparks birth vx 0.06
pdraw particle emitter sparks birth vz -0.04
pdraw particle emitter sparks collide solid
pdraw particle emitter sparks bounce 0.45
pdraw particle emitter sparks friction 0.65
pdraw particle emitter sparks appearance color 1,0.72,0.3,1
pdraw particle emitter sparks appearance glow true
pdraw particle emitter sparks appearance light 15
pdraw particle emitter sparks lifetime 240
pdraw particle emitter sparks rate 4
pdraw particle emitter sparks start
