pdraw particle emitter reset shell
pdraw particle emitter shell anchor fixed ~ ~3 ~
pdraw particle emitter shell sample grid
pdraw particle emitter shell sample bounds -2,-2,-2 2,2,2
pdraw particle emitter shell sample step 0.25
pdraw particle emitter shell where abs(sqrt(x*x+y*y+z*z)-2)<0.1
pdraw particle emitter shell motion vx x*0.01
pdraw particle emitter shell motion vy y*0.01
pdraw particle emitter shell motion vz z*0.01
pdraw particle emitter shell lifetime 60
pdraw particle emitter shell rate 512
pdraw particle emitter shell burst
