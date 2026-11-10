pdraw particle emitter reset sine
pdraw particle emitter sine anchor fixed ~ ~2 ~
pdraw particle emitter sine sample range -4 4 0.1
pdraw particle emitter sine birth x u
pdraw particle emitter sine birth y sin(u)
pdraw particle emitter sine motion y sin(u-age*0.1)
pdraw particle emitter sine texture particle:minecraft:end_rod
pdraw particle emitter sine appearance size_world 0.12,0.12
pdraw particle emitter sine appearance glowing true
pdraw particle emitter sine appearance light 0
pdraw particle emitter sine lifetime 80
pdraw particle emitter sine rate 81
pdraw particle emitter sine burst
