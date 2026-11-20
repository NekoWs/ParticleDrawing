# 方块遮挡 + 移动光源演示。
# 场地里放柱子、带缺口的矮墙和顶棚，两盏点光绕着柱子转，一盏射灯在顶棚下来回扫；
# 光源被方块挡住时地面会出现扫动的明暗边界，用来观察遮挡计算和光源移动时的重建表现。
# 客户端需要：动态光照开、maxShadowedParticleLights > 0（遮挡），想看光锥可开 showSpotlightBeams。
tp @s ~ ~ ~ 0 10
fill ~-15 ~-1 ~-5 ~15 ~12 ~23 minecraft:air
fill ~-15 ~-1 ~-5 ~15 ~-1 ~23 minecraft:stone_bricks
# 四周矮墙：挡住边缘的光，边界阴影更清楚
fill ~-15 ~ ~-5 ~15 ~2 ~-5 minecraft:stone_bricks
fill ~-15 ~ ~23 ~15 ~2 ~23 minecraft:stone_bricks
fill ~-15 ~ ~-5 ~-15 ~2 ~23 minecraft:stone_bricks
fill ~15 ~ ~-5 ~15 ~2 ~23 minecraft:stone_bricks
# 两根柱子：点光绕圈时会被周期性挡住
fill ~-6 ~ ~3 ~-5 ~4 ~4 minecraft:stone_bricks
fill ~5 ~ ~7 ~6 ~5 ~8 minecraft:stone_bricks
# 带缺口的矮墙：射灯扫过来时缺口漏光、实体部分挡光
fill ~-10 ~ ~15 ~10 ~3 ~15 minecraft:stone_bricks
fill ~-2 ~ ~15 ~2 ~3 ~15 minecraft:air
# 顶棚：射灯挂它下面，同时挡住往上的光
fill ~-8 ~7 ~9 ~8 ~7 ~17 minecraft:smooth_stone
gamerule minecraft:advance_time false
time set midnight
weather clear

# 移动点光 A：绕左柱转，橙光，被柱子挡住时地面阴影扫动
pdraw particle emitter reset occl_orbit_a
pdraw particle emitter occl_orbit_a anchor fixed ~ ~ ~
pdraw particle emitter occl_orbit_a sample count 1
pdraw particle emitter occl_orbit_a birth position vec3(0,0,0)
pdraw particle emitter occl_orbit_a orbit vec3(cos(t*0.00055)*7.5, 2.4, 5+sin(t*0.00055)*4.5)
pdraw particle emitter occl_orbit_a appearance style soft_dot
pdraw particle emitter occl_orbit_a appearance color #ff8a3d
pdraw particle emitter occl_orbit_a appearance size_world 0.42,0.42
pdraw particle emitter occl_orbit_a appearance glowing true
pdraw particle emitter occl_orbit_a appearance light 13
pdraw particle emitter occl_orbit_a appearance light_range 9
pdraw particle emitter occl_orbit_a appearance light_falloff smooth
pdraw particle emitter occl_orbit_a lifetime -1
pdraw particle emitter occl_orbit_a rate 1
pdraw particle emitter occl_orbit_a max_alive 1
pdraw particle emitter occl_orbit_a start

# 移动点光 B：反向绕右柱，青色，和 A 交错扫过同一片地面
pdraw particle emitter reset occl_orbit_b
pdraw particle emitter occl_orbit_b anchor fixed ~ ~ ~
pdraw particle emitter occl_orbit_b sample count 1
pdraw particle emitter occl_orbit_b birth position vec3(0,0,0)
pdraw particle emitter occl_orbit_b orbit vec3(cos(-t*0.00042)*6, 3.1, 9+sin(-t*0.00042)*4)
pdraw particle emitter occl_orbit_b appearance style soft_dot
pdraw particle emitter occl_orbit_b appearance color #5ff0d8
pdraw particle emitter occl_orbit_b appearance size_world 0.42,0.42
pdraw particle emitter occl_orbit_b appearance glowing true
pdraw particle emitter occl_orbit_b appearance light 13
pdraw particle emitter occl_orbit_b appearance light_range 9
pdraw particle emitter occl_orbit_b appearance light_falloff inverse
pdraw particle emitter occl_orbit_b lifetime -1
pdraw particle emitter occl_orbit_b rate 1
pdraw particle emitter occl_orbit_b max_alive 1
pdraw particle emitter occl_orbit_b start

# 固定射灯 A：从左上方斜照两根柱子，柱子背后留影子
pdraw particle emitter reset occl_spot_a
pdraw particle emitter occl_spot_a anchor fixed ~-6 ~6.4 ~9
pdraw particle emitter occl_spot_a sample count 1
pdraw particle emitter occl_spot_a birth position vec3(0,0,0)
pdraw particle emitter occl_spot_a appearance style soft_dot
pdraw particle emitter occl_spot_a appearance color #8ab4ff
pdraw particle emitter occl_spot_a appearance size_world 0.28,0.28
pdraw particle emitter occl_spot_a appearance glowing true
pdraw particle emitter occl_spot_a appearance light 15
pdraw particle emitter occl_spot_a appearance spot 30,10
pdraw particle emitter occl_spot_a appearance light_dir 25,-50
pdraw particle emitter occl_spot_a appearance light_range 14
pdraw particle emitter occl_spot_a appearance light_falloff smooth
pdraw particle emitter occl_spot_a lifetime -1
pdraw particle emitter occl_spot_a rate 1
pdraw particle emitter occl_spot_a max_alive 1
pdraw particle emitter occl_spot_a start

# 固定射灯 B：从右上方照带缺口的矮墙，缺口处漏光
pdraw particle emitter reset occl_spot_b
pdraw particle emitter occl_spot_b anchor fixed ~7 ~6.4 ~13
pdraw particle emitter occl_spot_b sample count 1
pdraw particle emitter occl_spot_b birth position vec3(0,0,0)
pdraw particle emitter occl_spot_b appearance style soft_dot
pdraw particle emitter occl_spot_b appearance color #ffd28a
pdraw particle emitter occl_spot_b appearance size_world 0.28,0.28
pdraw particle emitter occl_spot_b appearance glowing true
pdraw particle emitter occl_spot_b appearance light 15
pdraw particle emitter occl_spot_b appearance spot 35,14
pdraw particle emitter occl_spot_b appearance light_dir -35,-55
pdraw particle emitter occl_spot_b appearance light_range 14
pdraw particle emitter occl_spot_b appearance light_falloff constant
pdraw particle emitter occl_spot_b lifetime -1
pdraw particle emitter occl_spot_b rate 1
pdraw particle emitter occl_spot_b max_alive 1
pdraw particle emitter occl_spot_b start

# 扫描射灯：在顶棚下方左右来回，光斑扫过缺口矮墙
pdraw particle emitter reset occl_sweep
pdraw particle emitter occl_sweep anchor fixed ~ ~ ~
pdraw particle emitter occl_sweep sample count 1
pdraw particle emitter occl_sweep birth position vec3(0,0,0)
pdraw particle emitter occl_sweep motion position vec3(sin(t*0.00042)*5.5, 6.0, 12.5)
pdraw particle emitter occl_sweep appearance style soft_dot
pdraw particle emitter occl_sweep appearance color #ff5f8a
pdraw particle emitter occl_sweep appearance size_world 0.3,0.3
pdraw particle emitter occl_sweep appearance glowing true
pdraw particle emitter occl_sweep appearance light 15
pdraw particle emitter occl_sweep appearance spot 26,8
pdraw particle emitter occl_sweep appearance light_dir 0,-90
pdraw particle emitter occl_sweep appearance light_range 13
pdraw particle emitter occl_sweep appearance light_falloff smooth
pdraw particle emitter occl_sweep lifetime -1
pdraw particle emitter occl_sweep rate 1
pdraw particle emitter occl_sweep max_alive 1
pdraw particle emitter occl_sweep start
# 低角度射灯 C：几乎贴着地面朝 +Z 照，左柱会投出很长的影子，最容易看出遮挡
pdraw particle emitter reset occl_spot_c
pdraw particle emitter occl_spot_c anchor fixed ~-5.5 ~1.8 ~-1
pdraw particle emitter occl_spot_c sample count 1
pdraw particle emitter occl_spot_c birth position vec3(0,0,0)
pdraw particle emitter occl_spot_c appearance style soft_dot
pdraw particle emitter occl_spot_c appearance color #ffe08a
pdraw particle emitter occl_spot_c appearance size_world 0.26,0.26
pdraw particle emitter occl_spot_c appearance glowing true
pdraw particle emitter occl_spot_c appearance light 15
pdraw particle emitter occl_spot_c appearance spot 20,6
pdraw particle emitter occl_spot_c appearance light_dir 0,-6
pdraw particle emitter occl_spot_c appearance light_range 16
pdraw particle emitter occl_spot_c appearance light_falloff smooth
pdraw particle emitter occl_spot_c lifetime -1
pdraw particle emitter occl_spot_c rate 1
pdraw particle emitter occl_spot_c max_alive 1
pdraw particle emitter occl_spot_c start
