# 透镜：一颗 billboard 粒子，用内置参考贴图折射背后的画面；左右缓慢平移
pdraw particle emitter reset pd_lens_optics
pdraw particle emitter pd_lens_optics anchor fixed ~ ~1.6 ~8
pdraw particle emitter pd_lens_optics sample count 1
pdraw particle emitter pd_lens_optics rate 1
pdraw particle emitter pd_lens_optics lifetime 12000
pdraw particle emitter pd_lens_optics appearance distortion 32
pdraw particle emitter pd_lens_optics appearance size_world 5.3,5.3
pdraw particle emitter pd_lens_optics texture particle:particledrawing:lens
pdraw particle emitter pd_lens_optics motion x 1.62*sin(t/820)
pdraw particle emitter pd_lens_optics burst
# 边缘一圈小光点，只用来标出透镜轮廓
pdraw particle emitter reset pd_lens_rim
pdraw particle emitter pd_lens_rim anchor fixed ~ ~1.6 ~7.94
pdraw particle emitter pd_lens_rim sample count 192
pdraw particle emitter pd_lens_rim rate 192
pdraw particle emitter pd_lens_rim lifetime 12000
pdraw particle emitter pd_lens_rim appearance style soft_dot
pdraw particle emitter pd_lens_rim appearance color #70dafa
pdraw particle emitter pd_lens_rim appearance light 0
pdraw particle emitter pd_lens_rim appearance glowing true
pdraw particle emitter pd_lens_rim appearance size_world 0.068,0.068
pdraw particle emitter pd_lens_rim appearance additive true
pdraw particle emitter pd_lens_rim birth position vec3(1.62*sin(t/820)+2.63*cos(2*PI*u),2.63*sin(2*PI*u),0)
pdraw particle emitter pd_lens_rim motion position vec3(1.62*sin(t/820)+2.63*cos(2*PI*u),2.63*sin(2*PI*u),0)
pdraw particle emitter pd_lens_rim burst
