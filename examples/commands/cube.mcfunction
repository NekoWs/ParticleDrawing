# ColorBlock 立方体棱线：网格采样后按原始样本坐标保留十二条棱。
# 条件用 sx/sy/sz（采样原始坐标），出生公式改位置也不会影响筛选。
pdraw particle emitter reset cube
pdraw particle emitter cube anchor fixed ~ ~1 ~
pdraw particle emitter cube sample grid
pdraw particle emitter cube sample bounds -0.5,-0.5,-0.5 0.5,0.5,0.5
pdraw particle emitter cube sample step 0.1
pdraw particle emitter cube where (abs(sy)==0.5 && !(abs(sz)<0.5)) || (abs(sx)==0.5 && (!(abs(sz)<0.5) || !(abs(sy)<0.5)))
pdraw particle emitter cube birth r 0
pdraw particle emitter cube birth g 1
pdraw particle emitter cube birth b 1
pdraw particle emitter cube birth velocity vec3(0, 0.01, 0)
pdraw particle emitter cube texture particle:minecraft:end_rod
pdraw particle emitter cube appearance size_world 0.1,0.1
pdraw particle emitter cube appearance glowing true
pdraw particle emitter cube lifetime -1
pdraw particle emitter cube max_alive 8192
pdraw particle emitter cube rate 1024
pdraw particle emitter cube burst
