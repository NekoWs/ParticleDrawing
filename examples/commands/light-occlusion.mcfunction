# 白光源遮挡测试：只放普通白色光，不看颜色，只观察光是否稳定生效。
# 场地里 3 根柱子 + 一道带缺口矮墙；5 盏固定白光 + 1 盏移动白光。
# 关注点：某盏灯会不会整块不亮、过一阵又突然出现。
tp @s ~ ~ ~ 0 10
fill ~-15 ~-1 ~-5 ~15 ~12 ~23 minecraft:air
fill ~-15 ~-1 ~-5 ~15 ~-1 ~23 minecraft:stone_bricks
fill ~-15 ~ ~-5 ~15 ~2 ~-5 minecraft:stone_bricks
fill ~-15 ~ ~23 ~15 ~2 ~23 minecraft:stone_bricks
fill ~-15 ~ ~-5 ~-15 ~2 ~23 minecraft:stone_bricks
fill ~15 ~ ~-5 ~15 ~2 ~23 minecraft:stone_bricks
# 柱子
fill ~-6 ~ ~3 ~-5 ~4 ~4 minecraft:stone_bricks
fill ~5 ~ ~7 ~6 ~5 ~8 minecraft:stone_bricks
fill ~0 ~ ~11 ~1 ~3 ~12 minecraft:stone_bricks
# 带缺口的矮墙
fill ~-10 ~ ~16 ~10 ~3 ~16 minecraft:stone_bricks
fill ~-2 ~ ~16 ~2 ~3 ~16 minecraft:air

gamerule minecraft:advance_time false
time set midnight
weather clear

# 白光 1：左上方斜射
pdraw particle emitter reset light_a
pdraw particle emitter light_a anchor fixed ~-6 ~6.4 ~9
pdraw particle emitter light_a sample count 1
pdraw particle emitter light_a birth position vec3(0,0,0)
pdraw particle emitter light_a appearance style soft_dot
pdraw particle emitter light_a appearance color #ffffff
pdraw particle emitter light_a appearance size_world 0.3,0.3
pdraw particle emitter light_a appearance glowing true
pdraw particle emitter light_a appearance light 15
pdraw particle emitter light_a appearance spot 30,10
pdraw particle emitter light_a appearance light_dir 25,-50
pdraw particle emitter light_a appearance light_range 15
pdraw particle emitter light_a appearance light_falloff constant
pdraw particle emitter light_a lifetime -1
pdraw particle emitter light_a rate 1
pdraw particle emitter light_a max_alive 1
pdraw particle emitter light_a start

# 白光 2：右上方斜射
pdraw particle emitter reset light_b
pdraw particle emitter light_b anchor fixed ~7 ~6.4 ~13
pdraw particle emitter light_b sample count 1
pdraw particle emitter light_b birth position vec3(0,0,0)
pdraw particle emitter light_b appearance style soft_dot
pdraw particle emitter light_b appearance color #ffffff
pdraw particle emitter light_b appearance size_world 0.3,0.3
pdraw particle emitter light_b appearance glowing true
pdraw particle emitter light_b appearance light 15
pdraw particle emitter light_b appearance spot 35,14
pdraw particle emitter light_b appearance light_dir -35,-55
pdraw particle emitter light_b appearance light_range 15
pdraw particle emitter light_b appearance light_falloff constant
pdraw particle emitter light_b lifetime -1
pdraw particle emitter light_b rate 1
pdraw particle emitter light_b max_alive 1
pdraw particle emitter light_b start

# 白光 3：贴地掠射
pdraw particle emitter reset light_c
pdraw particle emitter light_c anchor fixed ~-5.5 ~1.8 ~-1
pdraw particle emitter light_c sample count 1
pdraw particle emitter light_c birth position vec3(0,0,0)
pdraw particle emitter light_c appearance style soft_dot
pdraw particle emitter light_c appearance color #ffffff
pdraw particle emitter light_c appearance size_world 0.3,0.3
pdraw particle emitter light_c appearance glowing true
pdraw particle emitter light_c appearance light 15
pdraw particle emitter light_c appearance spot 20,6
pdraw particle emitter light_c appearance light_dir 0,-6
pdraw particle emitter light_c appearance light_range 16
pdraw particle emitter light_c appearance light_falloff constant
pdraw particle emitter light_c lifetime -1
pdraw particle emitter light_c rate 1
pdraw particle emitter light_c max_alive 1
pdraw particle emitter light_c start

# 白光 4：场地中央点光
pdraw particle emitter reset light_d
pdraw particle emitter light_d anchor fixed ~-2 ~3.2 ~6
pdraw particle emitter light_d sample count 1
pdraw particle emitter light_d birth position vec3(0,0,0)
pdraw particle emitter light_d appearance style soft_dot
pdraw particle emitter light_d appearance color #ffffff
pdraw particle emitter light_d appearance size_world 0.3,0.3
pdraw particle emitter light_d appearance glowing true
pdraw particle emitter light_d appearance light 15
pdraw particle emitter light_d appearance light_range 12
pdraw particle emitter light_d appearance light_falloff constant
pdraw particle emitter light_d lifetime -1
pdraw particle emitter light_d rate 1
pdraw particle emitter light_d max_alive 1
pdraw particle emitter light_d start

# 白光 5：右侧点光
pdraw particle emitter reset light_e
pdraw particle emitter light_e anchor fixed ~8 ~3.2 ~10
pdraw particle emitter light_e sample count 1
pdraw particle emitter light_e birth position vec3(0,0,0)
pdraw particle emitter light_e appearance style soft_dot
pdraw particle emitter light_e appearance color #ffffff
pdraw particle emitter light_e appearance size_world 0.3,0.3
pdraw particle emitter light_e appearance glowing true
pdraw particle emitter light_e appearance light 15
pdraw particle emitter light_e appearance light_range 12
pdraw particle emitter light_e appearance light_falloff constant
pdraw particle emitter light_e lifetime -1
pdraw particle emitter light_e rate 1
pdraw particle emitter light_e max_alive 1
pdraw particle emitter light_e start

# 移动白光：顶棚下方左右扫
pdraw particle emitter reset light_move
pdraw particle emitter light_move anchor fixed ~ ~ ~
pdraw particle emitter light_move sample count 1
pdraw particle emitter light_move birth position vec3(0,0,0)
pdraw particle emitter light_move motion position vec3(sin(t*0.00042)*5.5, 6.0, 12.5)
pdraw particle emitter light_move appearance style soft_dot
pdraw particle emitter light_move appearance color #ffffff
pdraw particle emitter light_move appearance size_world 0.3,0.3
pdraw particle emitter light_move appearance glowing true
pdraw particle emitter light_move appearance light 15
pdraw particle emitter light_move appearance spot 26,8
pdraw particle emitter light_move appearance light_dir 0,-90
pdraw particle emitter light_move appearance light_range 13
pdraw particle emitter light_move appearance light_falloff smooth
pdraw particle emitter light_move lifetime -1
pdraw particle emitter light_move rate 1
pdraw particle emitter light_move max_alive 1
pdraw particle emitter light_move start
