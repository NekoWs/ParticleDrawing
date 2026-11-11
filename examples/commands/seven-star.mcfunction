# ColorBlock 七芒星：每刻 25 颗沿参数曲线画一段，41 刻画完。
pdraw particle emitter reset seven-star
pdraw particle emitter seven-star anchor fixed ~ ~1 ~
pdraw particle emitter seven-star sample range 0 1000 1
pdraw particle emitter seven-star birth position vec3((0.07*sin(0.04*u+0.225)-0.1*sin(-0.03*u+0.225))*50, 0, (0.07*cos(0.04*u+0.225)-0.1*cos(-0.03*u+0.225))*50)
pdraw particle emitter seven-star birth r 0.1
pdraw particle emitter seven-star birth g 1
pdraw particle emitter seven-star birth b 0.6
pdraw particle emitter seven-star texture particle:minecraft:end_rod
pdraw particle emitter seven-star appearance size_world 0.1,0.1
pdraw particle emitter seven-star appearance glowing true
pdraw particle emitter seven-star lifetime -1
pdraw particle emitter seven-star max_alive 1001
pdraw particle emitter seven-star rate 25
pdraw particle emitter seven-star burst
