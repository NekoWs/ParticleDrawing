# 九条曲臂，每臂 80 点；有界波形避免正切极点产生超远坐标。
pdraw particle emitter reset tentacles
pdraw particle emitter tentacles anchor fixed ~ ~2 ~
pdraw particle emitter tentacles sample count 720
pdraw particle emitter tentacles variable radius 4
pdraw particle emitter tentacles variable speed 0.003
pdraw particle emitter tentacles let arm floor(i/80)
pdraw particle emitter tentacles let along mod(i,80)/79
pdraw particle emitter tentacles let turn arm*2*PI/9+along*1.5+sin(t*speed+along*4)*0.25
pdraw particle emitter tentacles let reach 0.3+along*radius
pdraw particle emitter tentacles birth x reach*cos(turn)
pdraw particle emitter tentacles birth z reach*sin(turn)
pdraw particle emitter tentacles birth r 1
pdraw particle emitter tentacles birth g 0.15
pdraw particle emitter tentacles birth b 0.65
pdraw particle emitter tentacles motion x reach*cos(turn)
pdraw particle emitter tentacles motion y 0.25*sin(along*6-t*speed)
pdraw particle emitter tentacles motion z reach*sin(turn)
pdraw particle emitter tentacles motion a min(1,(life-age)/20)
pdraw particle emitter tentacles texture particle:minecraft:end_rod
pdraw particle emitter tentacles appearance size_world 0.08,0.08
pdraw particle emitter tentacles appearance glowing true
pdraw particle emitter tentacles appearance light 0
pdraw particle emitter tentacles follow true
pdraw particle emitter tentacles lifetime 200
pdraw particle emitter tentacles max_alive 1024
pdraw particle emitter tentacles rate 720
pdraw particle emitter tentacles burst
