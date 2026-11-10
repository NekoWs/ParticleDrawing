# 球面参数采样；以出生位置旋转，保持半径。
pdraw particle emitter reset sphere
pdraw particle emitter sphere anchor fixed ~ ~3 ~
pdraw particle emitter sphere sample count 768
pdraw particle emitter sphere variable radius 2.4
pdraw particle emitter sphere variable speed 0.035
pdraw particle emitter sphere let lat 1-2*u
pdraw particle emitter sphere let ring sqrt(max(0,1-lat*lat))
pdraw particle emitter sphere let longitude i*2.399963229728653
pdraw particle emitter sphere birth x radius*ring*cos(longitude)
pdraw particle emitter sphere birth y radius*lat
pdraw particle emitter sphere birth z radius*ring*sin(longitude)
pdraw particle emitter sphere birth r 0.2
pdraw particle emitter sphere birth g 1
pdraw particle emitter sphere birth b 0.8
pdraw particle emitter sphere motion x dx*cos(age*speed)-dz*sin(age*speed)
pdraw particle emitter sphere motion z dx*sin(age*speed)+dz*cos(age*speed)
pdraw particle emitter sphere motion r 0.5+0.5*sin(age*0.04+u*2*PI)
pdraw particle emitter sphere motion g 0.5+0.5*sin(age*0.04+u*2*PI+2*PI/3)
pdraw particle emitter sphere motion b 0.5+0.5*sin(age*0.04+u*2*PI+4*PI/3)
pdraw particle emitter sphere motion a min(1,(life-age)/20)
pdraw particle emitter sphere texture particle:minecraft:end_rod
pdraw particle emitter sphere appearance size_world 0.07,0.07
pdraw particle emitter sphere appearance glowing true
pdraw particle emitter sphere appearance light 0
pdraw particle emitter sphere follow true
pdraw particle emitter sphere lifetime 200
pdraw particle emitter sphere max_alive 1024
pdraw particle emitter sphere rate 768
pdraw particle emitter sphere burst
