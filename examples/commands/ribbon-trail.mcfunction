# 两颗粒子绕锚点转动，丝带沿其各自的世界轨迹延伸。
pdraw particle emitter reset ribbon_demo
pdraw particle emitter ribbon_demo anchor fixed ~ ~2 ~
pdraw particle emitter ribbon_demo sample count 2
pdraw particle emitter ribbon_demo let phase t/680+i*3.14159265
pdraw particle emitter ribbon_demo birth position vec3(2*cos(phase),0.7*sin(phase),0)
pdraw particle emitter ribbon_demo motion position vec3(2*cos(phase),0.7*sin(phase),0)
pdraw particle emitter ribbon_demo appearance style soft_dot
pdraw particle emitter ribbon_demo appearance color #ff9a3c
pdraw particle emitter ribbon_demo appearance size_world 0.26,0.26
pdraw particle emitter ribbon_demo appearance glowing true
pdraw particle emitter ribbon_demo appearance trail 32
pdraw particle emitter ribbon_demo lifetime 160
pdraw particle emitter ribbon_demo rate 2
pdraw particle emitter ribbon_demo burst
