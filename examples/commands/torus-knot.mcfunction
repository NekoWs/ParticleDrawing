# 三叶环面结，绕中心旋转后倾斜，保持曲线闭合与形状。
pdraw particle emitter reset knot
pdraw particle emitter knot anchor fixed ~ ~3 ~
pdraw particle emitter knot sample count 960
pdraw particle emitter knot variable speed 0.0004
pdraw particle emitter knot variable tilt 0.7
pdraw particle emitter knot let theta u*2*PI
pdraw particle emitter knot let radial 2+0.65*cos(3*theta)
pdraw particle emitter knot let px radial*cos(2*theta)
pdraw particle emitter knot let py 0.65*sin(3*theta)
pdraw particle emitter knot let pz radial*sin(2*theta)
pdraw particle emitter knot let phase t*speed
pdraw particle emitter knot let rx px*cos(phase)-pz*sin(phase)
pdraw particle emitter knot let rz px*sin(phase)+pz*cos(phase)
pdraw particle emitter knot birth x px
pdraw particle emitter knot birth y py*cos(tilt)-pz*sin(tilt)
pdraw particle emitter knot birth z py*sin(tilt)+pz*cos(tilt)
pdraw particle emitter knot birth r 0.6
pdraw particle emitter knot birth g 0.8
pdraw particle emitter knot birth b 1
pdraw particle emitter knot motion x rx
pdraw particle emitter knot motion y py*cos(tilt)-rz*sin(tilt)
pdraw particle emitter knot motion z py*sin(tilt)+rz*cos(tilt)
pdraw particle emitter knot motion r 0.55+0.4*cos(theta+t*0.001)
pdraw particle emitter knot motion g 0.5+0.35*sin(theta+t*0.001)
pdraw particle emitter knot motion b 0.85+0.15*cos(theta-t*0.001)
pdraw particle emitter knot motion a min(1,(life-age)/30)
pdraw particle emitter knot texture particle:minecraft:end_rod
pdraw particle emitter knot appearance size_world 0.065,0.065
pdraw particle emitter knot appearance glowing true
pdraw particle emitter knot appearance light 0
pdraw particle emitter knot follow true
pdraw particle emitter knot lifetime 240
pdraw particle emitter knot max_alive 1024
pdraw particle emitter knot rate 960
pdraw particle emitter knot burst
