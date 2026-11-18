# 子发射：粒子落到地面消失时，在原地炸出一批小火花。
# 子粒子只炸一层，不会再触发子发射；数量与速度上限见 subemit 命令。
# 配合 collide solid 用就是「陨石砸地」；去掉 collide 则是「空中消散」。
pdraw particle emitter reset meteor
pdraw particle emitter meteor anchor fixed ~ ~8 ~
pdraw particle emitter meteor sample count 1
pdraw particle emitter meteor birth position vec3((rand()-0.5)*8, 0, (rand()-0.5)*8)
pdraw particle emitter meteor birth vy -0.45
pdraw particle emitter meteor collide solid
pdraw particle emitter meteor bounce 0.05
pdraw particle emitter meteor subemit count 10
pdraw particle emitter meteor subemit lifetime 14
pdraw particle emitter meteor subemit speed 0.22
pdraw particle emitter meteor subemit scale 0.45
pdraw particle emitter meteor appearance scale 2.2
pdraw particle emitter meteor appearance color #ffb84d
pdraw particle emitter meteor appearance glowing true
pdraw particle emitter meteor appearance light 15
pdraw particle emitter meteor lifetime 26
pdraw particle emitter meteor rate 4
pdraw particle emitter meteor interval 2
pdraw particle emitter meteor start