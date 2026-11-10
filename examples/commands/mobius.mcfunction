# 144 个截面，每截面七个点；一圈后横向次序反转，形成单面扭带。
pdraw particle emitter reset mobius
pdraw particle emitter mobius anchor fixed ~ ~3 ~
pdraw particle emitter mobius sample count 1008
pdraw particle emitter mobius variable radius 2.4
pdraw particle emitter mobius variable width 1.4
pdraw particle emitter mobius variable speed 0.0003
pdraw particle emitter mobius variable tilt 0.55
pdraw particle emitter mobius let theta floor(i/7)*2*PI/144
pdraw particle emitter mobius let across (mod(i,7)/6-0.5)*width
pdraw particle emitter mobius let radial radius+across*cos(theta/2)
pdraw particle emitter mobius let px radial*cos(theta)
pdraw particle emitter mobius let py across*sin(theta/2)
pdraw particle emitter mobius let pz radial*sin(theta)
pdraw particle emitter mobius let phase t*speed
pdraw particle emitter mobius let rx px*cos(phase)-pz*sin(phase)
pdraw particle emitter mobius let rz px*sin(phase)+pz*cos(phase)
pdraw particle emitter mobius birth x px
pdraw particle emitter mobius birth y py*cos(tilt)-pz*sin(tilt)
pdraw particle emitter mobius birth z py*sin(tilt)+pz*cos(tilt)
pdraw particle emitter mobius birth r 0.4
pdraw particle emitter mobius birth g 0.8
pdraw particle emitter mobius birth b 1
pdraw particle emitter mobius motion x rx
pdraw particle emitter mobius motion y py*cos(tilt)-rz*sin(tilt)
pdraw particle emitter mobius motion z py*sin(tilt)+rz*cos(tilt)
pdraw particle emitter mobius motion r 0.4+0.3*sin(theta+t*0.001)
pdraw particle emitter mobius motion g 0.65+0.2*cos(theta)
pdraw particle emitter mobius motion a min(1,(life-age)/30)
pdraw particle emitter mobius texture particle:minecraft:end_rod
pdraw particle emitter mobius appearance size_world 0.065,0.065
pdraw particle emitter mobius appearance glowing true
pdraw particle emitter mobius appearance light 0
pdraw particle emitter mobius follow true
pdraw particle emitter mobius lifetime 240
pdraw particle emitter mobius max_alive 1024
pdraw particle emitter mobius rate 1008
pdraw particle emitter mobius burst
