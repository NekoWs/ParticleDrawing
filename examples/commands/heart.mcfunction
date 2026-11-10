# 参数曲线，分 10 刻左右画完；所有坐标相对锚点。
pdraw particle emitter reset heart
pdraw particle emitter heart anchor fixed ~ ~3 ~
pdraw particle emitter heart sample range -3.141592653589793 3.141592653589793 0.02
pdraw particle emitter heart birth x 16*pow(sin(u),3)/7
pdraw particle emitter heart birth y (13*cos(u)-5*cos(2*u)-2*cos(3*u)-cos(4*u))/7
pdraw particle emitter heart birth r 1
pdraw particle emitter heart birth g 0.2
pdraw particle emitter heart birth b 0.55
pdraw particle emitter heart motion sc 0.9+0.1*sin(t*0.005)
pdraw particle emitter heart motion a min(1,(life-age)/20)
pdraw particle emitter heart texture particle:minecraft:end_rod
pdraw particle emitter heart appearance size_world 0.09,0.09
pdraw particle emitter heart appearance glowing true
pdraw particle emitter heart appearance light 0
pdraw particle emitter heart follow true
pdraw particle emitter heart lifetime 160
pdraw particle emitter heart max_alive 512
pdraw particle emitter heart rate 32
pdraw particle emitter heart burst
