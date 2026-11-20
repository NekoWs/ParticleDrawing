# 方块遮挡 + 移动光源演示（斜向打墙 + 横向扫光）。
# 一排柱子站在大块平墙前面：上方几盏彩色射灯斜着打向墙面和地面，光斑有大片渐变；
# 柱子挡住光在墙上投出长影，两盏移动射灯横向来回扫，影子在墙上来回扫动。
# 客户端需要：动态光照开、maxShadowedParticleLights 足够大（这里 7 盏灯都算遮挡），
# showSpotlightBeams 开可看到光锥。
tp @s ~ ~ ~ 0 10
fill ~-16 ~-1 ~-8 ~16 ~16 ~32 minecraft:air
fill ~-16 ~-1 ~-8 ~16 ~-1 ~32 minecraft:stone_bricks
# 大墙：接收斜射光斑和柱子的影子
fill ~-16 ~ ~30 ~16 ~10 ~30 minecraft:stone_bricks
fill ~-16 ~ ~-8 ~-16 ~6 ~30 minecraft:stone_bricks
fill ~16 ~ ~-8 ~16 ~6 ~30 minecraft:stone_bricks
# 一排柱子（挡光、投影）：站在墙前 13 格
fill ~-10 ~ ~17 ~-9 ~4 ~17 minecraft:smooth_stone
fill ~-5 ~ ~17 ~-4 ~6 ~17 minecraft:smooth_stone
fill ~0 ~ ~17 ~1 ~3 ~17 minecraft:smooth_stone
fill ~5 ~ ~17 ~6 ~5 ~17 minecraft:smooth_stone
fill ~10 ~ ~17 ~11 ~7 ~17 minecraft:smooth_stone

gamerule minecraft:advance_time false
time set midnight
weather clear

# 斜射灯 A：左上方朝墙斜打，光斑落在墙上，柱子会切出长影
pdraw particle emitter reset occl_spot_a
pdraw particle emitter occl_spot_a anchor fixed ~-9 ~8.5 ~2
pdraw particle emitter occl_spot_a sample count 1
pdraw particle emitter occl_spot_a birth position vec3(0,0,0)
pdraw particle emitter occl_spot_a appearance style soft_dot
pdraw particle emitter occl_spot_a appearance color #c050ff
pdraw particle emitter occl_spot_a appearance size_world 0.28,0.28
pdraw particle emitter occl_spot_a appearance glowing true
pdraw particle emitter occl_spot_a appearance light 15
pdraw particle emitter occl_spot_a appearance spot 30,12
pdraw particle emitter occl_spot_a appearance light_dir 8,-42
pdraw particle emitter occl_spot_a appearance light_range 16
pdraw particle emitter occl_spot_a appearance light_falloff smooth
pdraw particle emitter occl_spot_a lifetime -1
pdraw particle emitter occl_spot_a rate 1
pdraw particle emitter occl_spot_a max_alive 1
pdraw particle emitter occl_spot_a start

# 斜射灯 B：中间偏上，暖黄，正对墙面
pdraw particle emitter reset occl_spot_b
pdraw particle emitter occl_spot_b anchor fixed ~0 ~9 ~1
pdraw particle emitter occl_spot_b sample count 1
pdraw particle emitter occl_spot_b birth position vec3(0,0,0)
pdraw particle emitter occl_spot_b appearance style soft_dot
pdraw particle emitter occl_spot_b appearance color #ffc04d
pdraw particle emitter occl_spot_b appearance size_world 0.28,0.28
pdraw particle emitter occl_spot_b appearance glowing true
pdraw particle emitter occl_spot_b appearance light 15
pdraw particle emitter occl_spot_b appearance spot 34,14
pdraw particle emitter occl_spot_b appearance light_dir 0,-38
pdraw particle emitter occl_spot_b appearance light_range 16
pdraw particle emitter occl_spot_b appearance light_falloff smooth
pdraw particle emitter occl_spot_b lifetime -1
pdraw particle emitter occl_spot_b rate 1
pdraw particle emitter occl_spot_b max_alive 1
pdraw particle emitter occl_spot_b start

# 斜射灯 C：右上方，青色，斜着扫到地面和墙交界
pdraw particle emitter reset occl_spot_c
pdraw particle emitter occl_spot_c anchor fixed ~9 ~8.5 ~2
pdraw particle emitter occl_spot_c sample count 1
pdraw particle emitter occl_spot_c birth position vec3(0,0,0)
pdraw particle emitter occl_spot_c appearance style soft_dot
pdraw particle emitter occl_spot_c appearance color #40e0ff
pdraw particle emitter occl_spot_c appearance size_world 0.28,0.28
pdraw particle emitter occl_spot_c appearance glowing true
pdraw particle emitter occl_spot_c appearance light 15
pdraw particle emitter occl_spot_c appearance spot 30,12
pdraw particle emitter occl_spot_c appearance light_dir -8,-42
pdraw particle emitter occl_spot_c appearance light_range 16
pdraw particle emitter occl_spot_c appearance light_falloff smooth
pdraw particle emitter occl_spot_c lifetime -1
pdraw particle emitter occl_spot_c rate 1
pdraw particle emitter occl_spot_c max_alive 1
pdraw particle emitter occl_spot_c start

# 贴地斜射长光：左侧低角度掠过地面，柱子拖出很长的影子
pdraw particle emitter reset occl_rake
pdraw particle emitter occl_rake anchor fixed ~-14 ~1.5 ~10
pdraw particle emitter occl_rake sample count 1
pdraw particle emitter occl_rake birth position vec3(0,0,0)
pdraw particle emitter occl_rake appearance style soft_dot
pdraw particle emitter occl_rake appearance color #ff5f5f
pdraw particle emitter occl_rake appearance size_world 0.28,0.28
pdraw particle emitter occl_rake appearance glowing true
pdraw particle emitter occl_rake appearance light 15
pdraw particle emitter occl_rake appearance spot 10,4
pdraw particle emitter occl_rake appearance light_dir 65,-6
pdraw particle emitter occl_rake appearance light_range 18
pdraw particle emitter occl_rake appearance light_falloff smooth
pdraw particle emitter occl_rake lifetime -1
pdraw particle emitter occl_rake rate 1
pdraw particle emitter occl_rake max_alive 1
pdraw particle emitter occl_rake start

# 横扫移动光 A：柱子前面横向来回，朝墙打，柱子影子在墙上扫动
pdraw particle emitter reset occl_sweep_a
pdraw particle emitter occl_sweep_a anchor fixed ~ ~ ~
pdraw particle emitter occl_sweep_a sample count 1
pdraw particle emitter occl_sweep_a birth position vec3(0,0,0)
pdraw particle emitter occl_sweep_a motion position vec3(sin(t*0.00032)*12, 2.6, 8)
pdraw particle emitter occl_sweep_a appearance style soft_dot
pdraw particle emitter occl_sweep_a appearance color #ffffff
pdraw particle emitter occl_sweep_a appearance size_world 0.32,0.32
pdraw particle emitter occl_sweep_a appearance glowing true
pdraw particle emitter occl_sweep_a appearance light 15
pdraw particle emitter occl_sweep_a appearance spot 22,9
pdraw particle emitter occl_sweep_a appearance light_dir 0,-8
pdraw particle emitter occl_sweep_a appearance light_range 16
pdraw particle emitter occl_sweep_a appearance light_falloff smooth
pdraw particle emitter occl_sweep_a lifetime -1
pdraw particle emitter occl_sweep_a rate 1
pdraw particle emitter occl_sweep_a max_alive 1
pdraw particle emitter occl_sweep_a start

# 横扫移动光 B：反相位、略低，两道光交错扫过同一排柱子
pdraw particle emitter reset occl_sweep_b
pdraw particle emitter occl_sweep_b anchor fixed ~ ~ ~
pdraw particle emitter occl_sweep_b sample count 1
pdraw particle emitter occl_sweep_b birth position vec3(0,0,0)
pdraw particle emitter occl_sweep_b motion position vec3(sin(-t*0.00032 + 1.2)*11, 2.0, 9)
pdraw particle emitter occl_sweep_b appearance style soft_dot
pdraw particle emitter occl_sweep_b appearance color #7fe8ff
pdraw particle emitter occl_sweep_b appearance size_world 0.32,0.32
pdraw particle emitter occl_sweep_b appearance glowing true
pdraw particle emitter occl_sweep_b appearance light 15
pdraw particle emitter occl_sweep_b appearance spot 26,10
pdraw particle emitter occl_sweep_b appearance light_dir 0,-10
pdraw particle emitter occl_sweep_b appearance light_range 16
pdraw particle emitter occl_sweep_b appearance light_falloff inverse
pdraw particle emitter occl_sweep_b lifetime -1
pdraw particle emitter occl_sweep_b rate 1
pdraw particle emitter occl_sweep_b max_alive 1
pdraw particle emitter occl_sweep_b start
