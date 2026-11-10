# 十二瓣玫瑰、双三角星与三重外环，共用 mandala 锚点。
pdraw particle emitter reset mandala
pdraw particle emitter mandala anchor fixed ~ ~2 ~
pdraw particle emitter mandala sample count 960
pdraw particle emitter mandala variable radius 2.6
pdraw particle emitter mandala variable speed 0.00035
pdraw particle emitter mandala let theta u*2*PI
pdraw particle emitter mandala let phase t*speed
pdraw particle emitter mandala let radial radius*cos(6*theta)
pdraw particle emitter mandala birth x radial*cos(theta)
pdraw particle emitter mandala birth z radial*sin(theta)
pdraw particle emitter mandala birth r 0.4
pdraw particle emitter mandala birth g 1
pdraw particle emitter mandala birth b 0.9
pdraw particle emitter mandala motion x radial*cos(theta+phase)
pdraw particle emitter mandala motion y 0.12*sin(theta*12+t*0.002)
pdraw particle emitter mandala motion z radial*sin(theta+phase)
pdraw particle emitter mandala motion r 0.45+0.3*sin(theta*2+t*0.001)
pdraw particle emitter mandala motion g 0.75+0.2*cos(theta*2+t*0.001)
pdraw particle emitter mandala motion a min(1,(life-age)/30)
pdraw particle emitter mandala texture particle:minecraft:end_rod
pdraw particle emitter mandala appearance size_world 0.055,0.055
pdraw particle emitter mandala appearance glowing true
pdraw particle emitter mandala appearance light 0
pdraw particle emitter mandala follow true
pdraw particle emitter mandala lifetime 240
pdraw particle emitter mandala max_alive 1024
pdraw particle emitter mandala rate 960

pdraw particle emitter reset mandala-star
pdraw particle emitter mandala-star anchor emitter mandala
pdraw particle emitter mandala-star sample count 360
pdraw particle emitter mandala-star let edge floor(i/60)
pdraw particle emitter mandala-star let along mod(i,60)/59
pdraw particle emitter mandala-star let corner mod(edge,3)*2*PI/3+floor(edge/3)*PI/3
pdraw particle emitter mandala-star let px 2.95*((1-along)*cos(corner)+along*cos(corner+2*PI/3))
pdraw particle emitter mandala-star let pz 2.95*((1-along)*sin(corner)+along*sin(corner+2*PI/3))
pdraw particle emitter mandala-star let phase -t*0.0002
pdraw particle emitter mandala-star birth x px
pdraw particle emitter mandala-star birth y 0.05
pdraw particle emitter mandala-star birth z pz
pdraw particle emitter mandala-star birth r 1
pdraw particle emitter mandala-star birth g 0.65
pdraw particle emitter mandala-star birth b 0.25
pdraw particle emitter mandala-star motion x px*cos(phase)-pz*sin(phase)
pdraw particle emitter mandala-star motion z px*sin(phase)+pz*cos(phase)
pdraw particle emitter mandala-star motion a min(1,(life-age)/30)
pdraw particle emitter mandala-star texture particle:minecraft:end_rod
pdraw particle emitter mandala-star appearance size_world 0.055,0.055
pdraw particle emitter mandala-star appearance glowing true
pdraw particle emitter mandala-star appearance light 0
pdraw particle emitter mandala-star follow true
pdraw particle emitter mandala-star lifetime 240
pdraw particle emitter mandala-star max_alive 512
pdraw particle emitter mandala-star rate 360

pdraw particle emitter reset mandala-ring
pdraw particle emitter mandala-ring anchor emitter mandala
pdraw particle emitter mandala-ring sample count 720
pdraw particle emitter mandala-ring let band floor(i/240)
pdraw particle emitter mandala-ring let theta mod(i,240)*2*PI/240
pdraw particle emitter mandala-ring let radial 3.2+band*0.18
pdraw particle emitter mandala-ring let phase -t*0.00025+band*0.04
pdraw particle emitter mandala-ring birth x radial*cos(theta+phase)
pdraw particle emitter mandala-ring birth y band*0.025
pdraw particle emitter mandala-ring birth z radial*sin(theta+phase)
pdraw particle emitter mandala-ring birth r 0.3
pdraw particle emitter mandala-ring birth g 0.8
pdraw particle emitter mandala-ring birth b 1
pdraw particle emitter mandala-ring motion x radial*cos(theta+phase)
pdraw particle emitter mandala-ring motion z radial*sin(theta+phase)
pdraw particle emitter mandala-ring motion a min(1,(life-age)/30)
pdraw particle emitter mandala-ring texture particle:minecraft:end_rod
pdraw particle emitter mandala-ring appearance size_world 0.04,0.04
pdraw particle emitter mandala-ring appearance glowing true
pdraw particle emitter mandala-ring appearance light 0
pdraw particle emitter mandala-ring follow true
pdraw particle emitter mandala-ring lifetime 240
pdraw particle emitter mandala-ring max_alive 1024
pdraw particle emitter mandala-ring rate 720

pdraw particle emitter mandala burst
pdraw particle emitter mandala-star burst
pdraw particle emitter mandala-ring burst
