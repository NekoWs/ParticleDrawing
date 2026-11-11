# ColorBlock 五角星：步长 2，样本一次画完。
pdraw particle emitter reset five-star
pdraw particle emitter five-star anchor fixed ~ ~1 ~
pdraw particle emitter five-star sample range 0 1000 2
pdraw particle emitter five-star birth position vec3((0.06*sin(0.02*u+1)-0.03*sin(-0.03*u+1))*20, 0, (0.06*cos(0.02*u+1)-0.03*cos(-0.03*u+1))*20)
pdraw particle emitter five-star birth r 1
pdraw particle emitter five-star birth g 0.5
pdraw particle emitter five-star birth b 0
pdraw particle emitter five-star texture particle:minecraft:end_rod
pdraw particle emitter five-star appearance size_world 0.1,0.1
pdraw particle emitter five-star appearance glowing true
pdraw particle emitter five-star lifetime -1
pdraw particle emitter five-star max_alive 501
pdraw particle emitter five-star rate 1024
pdraw particle emitter five-star burst
