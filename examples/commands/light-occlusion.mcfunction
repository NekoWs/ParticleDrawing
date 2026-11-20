# 方块遮挡 + 移动光源演示（横向扫光）。
# 一排高度不一的石柱站在场地中间，两盏射灯从左右两侧横着扫过柱子，影子在地面来回扫；
# 后面一排固定彩色射灯从背面照亮柱子，方便看不同颜色的遮挡边界。
# 客户端需要：动态光照开、maxShadowedParticleLights > 0（遮挡），showSpotlightBeams 开可看到光锥。
tp @s ~ ~ ~ 0 10
fill ~-16 ~-1 ~-8 ~16 ~14 ~22 minecraft:air
fill ~-16 ~-1 ~-8 ~16 ~-1 ~22 minecraft:stone_bricks
# 四周矮墙
fill ~-16 ~ ~-8 ~16 ~3 ~-8 minecraft:stone_bricks
fill ~-16 ~ ~22 ~16 ~5 ~22 minecraft:stone_bricks
fill ~-16 ~ ~-8 ~-16 ~3 ~22 minecraft:stone_bricks
fill ~16 ~ ~-8 ~16 ~3 ~22 minecraft:stone_bricks
# 一排高低不一的石柱（展台）：挡光、投长影
fill ~-11 ~ ~8 ~-10 ~3 ~8 minecraft:smooth_stone
fill ~-7 ~ ~8 ~-6 ~5 ~8 minecraft:smooth_stone
fill ~-3 ~ ~8 ~-2 ~3 ~8 minecraft:smooth_stone
fill ~1 ~ ~8 ~2 ~5 ~8 minecraft:smooth_stone
fill ~5 ~ ~8 ~6 ~4 ~8 minecraft:smooth_stone
fill ~9 ~ ~8 ~10 ~6 ~8 minecraft:smooth_stone

gamerule minecraft:advance_time false
time set midnight
weather clear

# 固定彩色射灯：在柱子背面朝 +Z 照，每根柱子一盏，影子朝玩家方向投
pdraw particle emitter reset occl_spot_a
pdraw particle emitter occl_spot_a anchor fixed ~-10.5 ~2.6 ~13.5
pdraw particle emitter occl_spot_a sample count 1
pdraw particle emitter occl_spot_a birth position vec3(0,0,0)
pdraw particle emitter occl_spot_a appearance style soft_dot
pdraw particle emitter occl_spot_a appearance color #ff4040
pdraw particle emitter occl_spot_a appearance size_world 0.26,0.26
pdraw particle emitter occl_spot_a appearance glowing true
pdraw particle emitter occl_spot_a appearance light 15
pdraw particle emitter occl_spot_a appearance spot 16,6
pdraw particle emitter occl_spot_a appearance light_dir 180,-8
pdraw particle emitter occl_spot_a appearance light_range 14
pdraw particle emitter occl_spot_a appearance light_falloff smooth
pdraw particle emitter occl_spot_a lifetime -1
pdraw particle emitter occl_spot_a rate 1
pdraw particle emitter occl_spot_a max_alive 1
pdraw particle emitter occl_spot_a start

pdraw particle emitter reset occl_spot_b
pdraw particle emitter occl_spot_b anchor fixed ~-6.5 ~2.6 ~13.5
pdraw particle emitter occl_spot_b sample count 1
pdraw particle emitter occl_spot_b birth position vec3(0,0,0)
pdraw particle emitter occl_spot_b appearance style soft_dot
pdraw particle emitter occl_spot_b appearance color #ffb040
pdraw particle emitter occl_spot_b appearance size_world 0.26,0.26
pdraw particle emitter occl_spot_b appearance glowing true
pdraw particle emitter occl_spot_b appearance light 15
pdraw particle emitter occl_spot_b appearance spot 16,6
pdraw particle emitter occl_spot_b appearance light_dir 180,-8
pdraw particle emitter occl_spot_b appearance light_range 14
pdraw particle emitter occl_spot_b appearance light_falloff smooth
pdraw particle emitter occl_spot_b lifetime -1
pdraw particle emitter occl_spot_b rate 1
pdraw particle emitter occl_spot_b max_alive 1
pdraw particle emitter occl_spot_b start

pdraw particle emitter reset occl_spot_c
pdraw particle emitter occl_spot_c anchor fixed ~-2.5 ~2.6 ~13.5
pdraw particle emitter occl_spot_c sample count 1
pdraw particle emitter occl_spot_c birth position vec3(0,0,0)
pdraw particle emitter occl_spot_c appearance style soft_dot
pdraw particle emitter occl_spot_c appearance color #6cff5a
pdraw particle emitter occl_spot_c appearance size_world 0.26,0.26
pdraw particle emitter occl_spot_c appearance glowing true
pdraw particle emitter occl_spot_c appearance light 15
pdraw particle emitter occl_spot_c appearance spot 16,6
pdraw particle emitter occl_spot_c appearance light_dir 180,-8
pdraw particle emitter occl_spot_c appearance light_range 14
pdraw particle emitter occl_spot_c appearance light_falloff smooth
pdraw particle emitter occl_spot_c lifetime -1
pdraw particle emitter occl_spot_c rate 1
pdraw particle emitter occl_spot_c max_alive 1
pdraw particle emitter occl_spot_c start

pdraw particle emitter reset occl_spot_d
pdraw particle emitter occl_spot_d anchor fixed ~1.5 ~2.6 ~13.5
pdraw particle emitter occl_spot_d sample count 1
pdraw particle emitter occl_spot_d birth position vec3(0,0,0)
pdraw particle emitter occl_spot_d appearance style soft_dot
pdraw particle emitter occl_spot_d appearance color #40e0ff
pdraw particle emitter occl_spot_d appearance size_world 0.26,0.26
pdraw particle emitter occl_spot_d appearance glowing true
pdraw particle emitter occl_spot_d appearance light 15
pdraw particle emitter occl_spot_d appearance spot 16,6
pdraw particle emitter occl_spot_d appearance light_dir 180,-8
pdraw particle emitter occl_spot_d appearance light_range 14
pdraw particle emitter occl_spot_d appearance light_falloff smooth
pdraw particle emitter occl_spot_d lifetime -1
pdraw particle emitter occl_spot_d rate 1
pdraw particle emitter occl_spot_d max_alive 1
pdraw particle emitter occl_spot_d start

pdraw particle emitter reset occl_spot_e
pdraw particle emitter occl_spot_e anchor fixed ~5.5 ~2.6 ~13.5
pdraw particle emitter occl_spot_e sample count 1
pdraw particle emitter occl_spot_e birth position vec3(0,0,0)
pdraw particle emitter occl_spot_e appearance style soft_dot
pdraw particle emitter occl_spot_e appearance color #4a8dff
pdraw particle emitter occl_spot_e appearance size_world 0.26,0.26
pdraw particle emitter occl_spot_e appearance glowing true
pdraw particle emitter occl_spot_e appearance light 15
pdraw particle emitter occl_spot_e appearance spot 16,6
pdraw particle emitter occl_spot_e appearance light_dir 180,-8
pdraw particle emitter occl_spot_e appearance light_range 14
pdraw particle emitter occl_spot_e appearance light_falloff smooth
pdraw particle emitter occl_spot_e lifetime -1
pdraw particle emitter occl_spot_e rate 1
pdraw particle emitter occl_spot_e max_alive 1
pdraw particle emitter occl_spot_e start

pdraw particle emitter reset occl_spot_f
pdraw particle emitter occl_spot_f anchor fixed ~9.5 ~2.6 ~13.5
pdraw particle emitter occl_spot_f sample count 1
pdraw particle emitter occl_spot_f birth position vec3(0,0,0)
pdraw particle emitter occl_spot_f appearance style soft_dot
pdraw particle emitter occl_spot_f appearance color #c050ff
pdraw particle emitter occl_spot_f appearance size_world 0.26,0.26
pdraw particle emitter occl_spot_f appearance glowing true
pdraw particle emitter occl_spot_f appearance light 15
pdraw particle emitter occl_spot_f appearance spot 16,6
pdraw particle emitter occl_spot_f appearance light_dir 180,-8
pdraw particle emitter occl_spot_f appearance light_range 14
pdraw particle emitter occl_spot_f appearance light_falloff smooth
pdraw particle emitter occl_spot_f lifetime -1
pdraw particle emitter occl_spot_f rate 1
pdraw particle emitter occl_spot_f max_alive 1
pdraw particle emitter occl_spot_f start

# 横扫移动光 A：从左侧贴着地面朝 +X 照，沿 Z 左右扫，柱子挡住时影子在地面扫过
pdraw particle emitter reset occl_sweep_a
pdraw particle emitter occl_sweep_a anchor fixed ~ ~ ~
pdraw particle emitter occl_sweep_a sample count 1
pdraw particle emitter occl_sweep_a birth position vec3(0,0,0)
pdraw particle emitter occl_sweep_a motion position vec3(sin(t*0.00035)*12, 2.8, 13.5)
pdraw particle emitter occl_sweep_a appearance style soft_dot
pdraw particle emitter occl_sweep_a appearance color #ffffff
pdraw particle emitter occl_sweep_a appearance size_world 0.34,0.34
pdraw particle emitter occl_sweep_a appearance glowing true
pdraw particle emitter occl_sweep_a appearance light 15
pdraw particle emitter occl_sweep_a appearance spot 14,5
pdraw particle emitter occl_sweep_a appearance light_dir 180,-10
pdraw particle emitter occl_sweep_a appearance light_range 16
pdraw particle emitter occl_sweep_a appearance light_falloff smooth
pdraw particle emitter occl_sweep_a lifetime -1
pdraw particle emitter occl_sweep_a rate 1
pdraw particle emitter occl_sweep_a max_alive 1
pdraw particle emitter occl_sweep_a start

# 横扫移动光 B：从右侧反向扫，两道光交错
pdraw particle emitter reset occl_sweep_b
pdraw particle emitter occl_sweep_b anchor fixed ~ ~ ~
pdraw particle emitter occl_sweep_b sample count 1
pdraw particle emitter occl_sweep_b birth position vec3(0,0,0)
pdraw particle emitter occl_sweep_b motion position vec3(sin(-t*0.00035 + 1.2)*11, 3.4, 13.5)
pdraw particle emitter occl_sweep_b appearance style soft_dot
pdraw particle emitter occl_sweep_b appearance color #7fe8ff
pdraw particle emitter occl_sweep_b appearance size_world 0.34,0.34
pdraw particle emitter occl_sweep_b appearance glowing true
pdraw particle emitter occl_sweep_b appearance light 15
pdraw particle emitter occl_sweep_b appearance spot 18,6
pdraw particle emitter occl_sweep_b appearance light_dir 180,-12
pdraw particle emitter occl_sweep_b appearance light_range 16
pdraw particle emitter occl_sweep_b appearance light_falloff inverse
pdraw particle emitter occl_sweep_b lifetime -1
pdraw particle emitter occl_sweep_b rate 1
pdraw particle emitter occl_sweep_b max_alive 1
pdraw particle emitter occl_sweep_b start

