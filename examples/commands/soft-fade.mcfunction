# 在平整地板上执行。两组使用同一颜色、大小和高度，右侧启用贴地淡出。
pdraw particle emitter reset soft_reference
pdraw particle emitter soft_reference anchor fixed ~-2.4 ~0.34 ~
pdraw particle emitter soft_reference sample count 5
pdraw particle emitter soft_reference birth position vec3(i*0.55-1.1,0,0)
pdraw particle emitter soft_reference appearance style soft_dot
pdraw particle emitter soft_reference appearance color #48e0ff
pdraw particle emitter soft_reference appearance size_world 0.64,0.9
pdraw particle emitter soft_reference appearance glowing true
pdraw particle emitter soft_reference appearance soft 0
pdraw particle emitter soft_reference lifetime 160
pdraw particle emitter soft_reference rate 5
pdraw particle emitter soft_reference burst
pdraw particle emitter reset soft_demo
pdraw particle emitter soft_demo anchor fixed ~2.4 ~0.34 ~
pdraw particle emitter soft_demo sample count 5
pdraw particle emitter soft_demo birth position vec3(i*0.55-1.1,0,0)
pdraw particle emitter soft_demo appearance style soft_dot
pdraw particle emitter soft_demo appearance color #48e0ff
pdraw particle emitter soft_demo appearance size_world 0.64,0.9
pdraw particle emitter soft_demo appearance glowing true
pdraw particle emitter soft_demo appearance soft 0.9
pdraw particle emitter soft_demo lifetime 160
pdraw particle emitter soft_demo rate 5
pdraw particle emitter soft_demo burst
