# 单盏斜射白光：只留一盏从上方斜打下来的射灯，用来观察衰减与遮挡。
# 平滑衰减（smooth），没有其他光源干扰；场地里的柱子和缺口墙会切出影子。
tp @s ~ ~ ~ 0 10
fill ~-15 ~-1 ~-5 ~15 ~12 ~23 minecraft:air
fill ~-15 ~-1 ~-5 ~15 ~-1 ~23 minecraft:stone_bricks
fill ~-15 ~ ~-5 ~15 ~2 ~-5 minecraft:stone_bricks
fill ~-15 ~ ~23 ~15 ~2 ~23 minecraft:stone_bricks
fill ~-15 ~ ~-5 ~-15 ~2 ~23 minecraft:stone_bricks
fill ~15 ~ ~-5 ~15 ~2 ~23 minecraft:stone_bricks
# 柱子（挡光、投影）
fill ~-6 ~ ~3 ~-5 ~4 ~4 minecraft:stone_bricks
fill ~5 ~ ~7 ~6 ~5 ~8 minecraft:stone_bricks
fill ~0 ~ ~11 ~1 ~3 ~12 minecraft:stone_bricks
# 带缺口的矮墙
fill ~-10 ~ ~16 ~10 ~3 ~16 minecraft:stone_bricks
fill ~-2 ~ ~16 ~2 ~3 ~16 minecraft:air

gamerule minecraft:advance_time false
time set midnight
weather clear

# 唯一的斜射白光：左上方斜着打向地面与矮墙，锥角 30°，平滑衰减
pdraw particle emitter reset light_main
pdraw particle emitter light_main anchor fixed ~-6 ~6.4 ~9
pdraw particle emitter light_main sample count 1
pdraw particle emitter light_main birth position vec3(0,0,0)
pdraw particle emitter light_main appearance style soft_dot
pdraw particle emitter light_main appearance color #ffffff
pdraw particle emitter light_main appearance size_world 0.3,0.3
pdraw particle emitter light_main appearance glowing true
pdraw particle emitter light_main appearance light 15
pdraw particle emitter light_main appearance spot 30,10
pdraw particle emitter light_main appearance light_dir 25,-50
pdraw particle emitter light_main appearance light_range 15
pdraw particle emitter light_main appearance light_falloff smooth
pdraw particle emitter light_main lifetime -1
pdraw particle emitter light_main rate 1
pdraw particle emitter light_main max_alive 1
pdraw particle emitter light_main start
