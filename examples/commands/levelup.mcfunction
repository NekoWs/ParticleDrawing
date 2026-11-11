# 升级动画：脚下粒子环 → 向上划过 → 拖尾残影（残影由少到多再变少）。
# 三个发射器各占 1/3 圈、每个角度一列 13 颗滞后粒子，合起来约 6100 颗，两刻内全部出生。
# 想更密就把 sample count 提到 6667（单帧上限 8192），rate 保持 1024。
#
# 结构：u 先被放大 512 倍，整数部分决定角度，小数部分决定这颗在拖尾里的滞后量。
# 于是每个角度上都有一列粒子，列与列之间才连成一条平滑上升的带子。
pdraw particle emitter reset levelup_a
pdraw particle emitter reset levelup_b
pdraw particle emitter reset levelup_c
pdraw particle emitter levelup_a anchor fixed ~ ~ ~
pdraw particle emitter levelup_b anchor fixed ~ ~ ~
pdraw particle emitter levelup_c anchor fixed ~ ~ ~
pdraw particle emitter levelup_a sample count 2048
pdraw particle emitter levelup_b sample count 2048
pdraw particle emitter levelup_c sample count 2048
pdraw particle emitter levelup_a let slot u*512
pdraw particle emitter levelup_b let slot u*512
pdraw particle emitter levelup_c let slot u*512
pdraw particle emitter levelup_a let col floor(slot)
pdraw particle emitter levelup_b let col floor(slot)
pdraw particle emitter levelup_c let col floor(slot)
pdraw particle emitter levelup_a let sub fract(slot)
pdraw particle emitter levelup_b let sub fract(slot)
pdraw particle emitter levelup_c let sub fract(slot)
pdraw particle emitter levelup_a let ang (col/512+0)*6.2832
pdraw particle emitter levelup_b let ang (col/512+0.3333)*6.2832
pdraw particle emitter levelup_c let ang (col/512+0.6667)*6.2832
# ph 是整段动画的进度；head 是头顶高度；trail 是这一刻拖尾该有多长（正弦包络，两头为 0）。
pdraw particle emitter levelup_a let ph min(age/60,1)
pdraw particle emitter levelup_b let ph min(age/60,1)
pdraw particle emitter levelup_c let ph min(age/60,1)
pdraw particle emitter levelup_a let head ph*7
pdraw particle emitter levelup_b let head ph*7
pdraw particle emitter levelup_c let head ph*7
pdraw particle emitter levelup_a let trail sin(ph*3.1416)*2.6
pdraw particle emitter levelup_b let trail sin(ph*3.1416)*2.6
pdraw particle emitter levelup_c let trail sin(ph*3.1416)*2.6
# lag 是这颗粒子落后头部的距离：头部落下多深，它就在多高；还没被"经过"的留在地面组成环。
pdraw particle emitter levelup_a let lag sub*2.6
pdraw particle emitter levelup_b let lag sub*2.6
pdraw particle emitter levelup_c let lag sub*2.6
pdraw particle emitter levelup_a let h max(head-lag,0)
pdraw particle emitter levelup_b let h max(head-lag,0)
pdraw particle emitter levelup_c let h max(head-lag,0)
pdraw particle emitter levelup_a let rad (2.2+1.5*sin(ph*3.1416))*(1-lag/7)
pdraw particle emitter levelup_b let rad (2.2+1.5*sin(ph*3.1416))*(1-lag/7)
pdraw particle emitter levelup_c let rad (2.2+1.5*sin(ph*3.1416))*(1-lag/7)
pdraw particle emitter levelup_a birth position vec3(cos(ang)*rad,0.05,sin(ang)*rad)
pdraw particle emitter levelup_b birth position vec3(cos(ang)*rad,0.05,sin(ang)*rad)
pdraw particle emitter levelup_c birth position vec3(cos(ang)*rad,0.05,sin(ang)*rad)
pdraw particle emitter levelup_a birth r 1
pdraw particle emitter levelup_b birth r 1
pdraw particle emitter levelup_c birth r 1
pdraw particle emitter levelup_a birth g 0.82
pdraw particle emitter levelup_b birth g 0.82
pdraw particle emitter levelup_c birth g 0.82
pdraw particle emitter levelup_a birth b 0.35
pdraw particle emitter levelup_b birth b 0.35
pdraw particle emitter levelup_c birth b 0.35
pdraw particle emitter levelup_a motion position vec3(cos(ang)*rad,h,sin(ang)*rad)
pdraw particle emitter levelup_b motion position vec3(cos(ang)*rad,h,sin(ang)*rad)
pdraw particle emitter levelup_c motion position vec3(cos(ang)*rad,h,sin(ang)*rad)
# 越靠后的残影越淡；整段推进到后半程整体再收一点，收尾时只剩头部。
pdraw particle emitter levelup_a motion a clamp((1-lag/max(trail,0.08))*(1-ph*0.25),0,1)*0.95
pdraw particle emitter levelup_b motion a clamp((1-lag/max(trail,0.08))*(1-ph*0.25),0,1)*0.95
pdraw particle emitter levelup_c motion a clamp((1-lag/max(trail,0.08))*(1-ph*0.25),0,1)*0.95
pdraw particle emitter levelup_a motion g 0.68+0.32*(1-lag/2.6)
pdraw particle emitter levelup_b motion g 0.68+0.32*(1-lag/2.6)
pdraw particle emitter levelup_c motion g 0.68+0.32*(1-lag/2.6)
pdraw particle emitter levelup_a motion b 0.18+0.62*(1-lag/2.6)
pdraw particle emitter levelup_b motion b 0.18+0.62*(1-lag/2.6)
pdraw particle emitter levelup_c motion b 0.18+0.62*(1-lag/2.6)
pdraw particle emitter levelup_a motion sc 0.55+0.45*(1-lag/2.6)
pdraw particle emitter levelup_b motion sc 0.55+0.45*(1-lag/2.6)
pdraw particle emitter levelup_c motion sc 0.55+0.45*(1-lag/2.6)
pdraw particle emitter levelup_a motion glow 1
pdraw particle emitter levelup_b motion glow 1
pdraw particle emitter levelup_c motion glow 1
pdraw particle emitter levelup_a appearance size_world 0.12,0.12
pdraw particle emitter levelup_b appearance size_world 0.12,0.12
pdraw particle emitter levelup_c appearance size_world 0.12,0.12
pdraw particle emitter levelup_a appearance glowing true
pdraw particle emitter levelup_b appearance glowing true
pdraw particle emitter levelup_c appearance glowing true
pdraw particle emitter levelup_a appearance light 10
pdraw particle emitter levelup_b appearance light 10
pdraw particle emitter levelup_c appearance light 10
pdraw particle emitter levelup_a lifetime 60
pdraw particle emitter levelup_b lifetime 60
pdraw particle emitter levelup_c lifetime 60
pdraw particle emitter levelup_a max_alive 2048
pdraw particle emitter levelup_b max_alive 2048
pdraw particle emitter levelup_c max_alive 2048
pdraw particle emitter levelup_a rate 1024
pdraw particle emitter levelup_b rate 1024
pdraw particle emitter levelup_c rate 1024
pdraw particle emitter levelup_a burst
pdraw particle emitter levelup_b burst
pdraw particle emitter levelup_c burst
