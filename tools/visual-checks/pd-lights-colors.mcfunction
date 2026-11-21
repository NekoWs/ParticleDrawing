# 彩色光源测试场景：4 盏固定彩色射灯 + 2 盏移动彩色点光 + 1 盏白色参考灯。
# 用来观察“某个彩色光源整块不生效、过一阵又突然出现”能否复现。
# 白灯用来对照：如果白灯亮、某盏彩灯不亮，问题就在那盏灯的收集/遮挡环节。
tp @s ~ ~ ~ 0 10
fill ~-15 ~-1 ~-5 ~15 ~12 ~23 minecraft:air
fill ~-15 ~-1 ~-5 ~15 ~-1 ~23 minecraft:stone_bricks
fill ~-15 ~ ~-5 ~15 ~2 ~-5 minecraft:stone_bricks
fill ~-15 ~ ~23 ~15 ~2 ~23 minecraft:stone_bricks
fill ~-15 ~ ~-5 ~-15 ~2 ~23 minecraft:stone_bricks
fill ~15 ~ ~-5 ~15 ~2 ~23 minecraft:stone_bricks
# 柱子与带缺口的矮墙：让光有东西可挡，影子和亮区更容易分辨
fill ~-6 ~ ~3 ~-5 ~4 ~4 minecraft:stone_bricks
fill ~5 ~ ~7 ~6 ~5 ~8 minecraft:stone_bricks
fill ~-10 ~ ~15 ~10 ~3 ~15 minecraft:stone_bricks
fill ~-2 ~ ~15 ~2 ~3 ~15 minecraft:air

gamerule minecraft:advance_time false
time set midnight
weather clear

pdraw particle emitter reset spot_red
pdraw particle emitter spot_red anchor fixed ~-6 ~6.4 ~9
pdraw particle emitter spot_red sample count 1
pdraw particle emitter spot_red birth position vec3(0,0,0)
pdraw particle emitter spot_red appearance style soft_dot
pdraw particle emitter spot_red appearance color #ff4040
pdraw particle emitter spot_red appearance size_world 0.3,0.3
pdraw particle emitter spot_red appearance glowing true
pdraw particle emitter spot_red appearance light 15
pdraw particle emitter spot_red appearance spot 30,10
pdraw particle emitter spot_red appearance light_dir 25,-50
pdraw particle emitter spot_red appearance light_range 15
pdraw particle emitter spot_red appearance light_falloff constant
pdraw particle emitter spot_red lifetime -1
pdraw particle emitter spot_red rate 1
pdraw particle emitter spot_red max_alive 1
pdraw particle emitter spot_red start

pdraw particle emitter reset spot_green
pdraw particle emitter spot_green anchor fixed ~7 ~6.4 ~13
pdraw particle emitter spot_green sample count 1
pdraw particle emitter spot_green birth position vec3(0,0,0)
pdraw particle emitter spot_green appearance style soft_dot
pdraw particle emitter spot_green appearance color #40ff60
pdraw particle emitter spot_green appearance size_world 0.3,0.3
pdraw particle emitter spot_green appearance glowing true
pdraw particle emitter spot_green appearance light 15
pdraw particle emitter spot_green appearance spot 35,14
pdraw particle emitter spot_green appearance light_dir -35,-55
pdraw particle emitter spot_green appearance light_range 15
pdraw particle emitter spot_green appearance light_falloff constant
pdraw particle emitter spot_green lifetime -1
pdraw particle emitter spot_green rate 1
pdraw particle emitter spot_green max_alive 1
pdraw particle emitter spot_green start

pdraw particle emitter reset spot_blue
pdraw particle emitter spot_blue anchor fixed ~0 ~7 ~20
pdraw particle emitter spot_blue sample count 1
pdraw particle emitter spot_blue birth position vec3(0,0,0)
pdraw particle emitter spot_blue appearance style soft_dot
pdraw particle emitter spot_blue appearance color #4060ff
pdraw particle emitter spot_blue appearance size_world 0.3,0.3
pdraw particle emitter spot_blue appearance glowing true
pdraw particle emitter spot_blue appearance light 15
pdraw particle emitter spot_blue appearance spot 40,15
pdraw particle emitter spot_blue appearance light_dir 0,-45
pdraw particle emitter spot_blue appearance light_range 15
pdraw particle emitter spot_blue appearance light_falloff smooth
pdraw particle emitter spot_blue lifetime -1
pdraw particle emitter spot_blue rate 1
pdraw particle emitter spot_blue max_alive 1
pdraw particle emitter spot_blue start

pdraw particle emitter reset spot_amber
pdraw particle emitter spot_amber anchor fixed ~-10 ~5 ~18
pdraw particle emitter spot_amber sample count 1
pdraw particle emitter spot_amber birth position vec3(0,0,0)
pdraw particle emitter spot_amber appearance style soft_dot
pdraw particle emitter spot_amber appearance color #ffb040
pdraw particle emitter spot_amber appearance size_world 0.3,0.3
pdraw particle emitter spot_amber appearance glowing true
pdraw particle emitter spot_amber appearance light 15
pdraw particle emitter spot_amber appearance spot 25,8
pdraw particle emitter spot_amber appearance light_dir 60,-35
pdraw particle emitter spot_amber appearance light_range 14
pdraw particle emitter spot_amber appearance light_falloff smooth
pdraw particle emitter spot_amber lifetime -1
pdraw particle emitter spot_amber rate 1
pdraw particle emitter spot_amber max_alive 1
pdraw particle emitter spot_amber start

pdraw particle emitter reset point_magenta
pdraw particle emitter point_magenta anchor fixed ~ ~ ~
pdraw particle emitter point_magenta sample count 1
pdraw particle emitter point_magenta birth position vec3(0,0,0)
pdraw particle emitter point_magenta birth position vec3(-8, 2.5, 10)
pdraw particle emitter point_magenta motion x -8+sin(t*0.00040)*6
pdraw particle emitter point_magenta motion y 2.5
pdraw particle emitter point_magenta motion z 10+cos(t*0.00040)*3
pdraw particle emitter point_magenta appearance style soft_dot
pdraw particle emitter point_magenta appearance color #ff40ff
pdraw particle emitter point_magenta appearance size_world 0.3,0.3
pdraw particle emitter point_magenta appearance glowing true
pdraw particle emitter point_magenta appearance light 15
pdraw particle emitter point_magenta appearance light_range 12
pdraw particle emitter point_magenta appearance light_falloff constant
pdraw particle emitter point_magenta lifetime -1
pdraw particle emitter point_magenta rate 1
pdraw particle emitter point_magenta max_alive 1
pdraw particle emitter point_magenta start

pdraw particle emitter reset point_cyan
pdraw particle emitter point_cyan anchor fixed ~ ~ ~
pdraw particle emitter point_cyan sample count 1
pdraw particle emitter point_cyan birth position vec3(0,0,0)
pdraw particle emitter point_cyan birth position vec3(8, 2.5, 10)
pdraw particle emitter point_cyan motion x 8+sin(t*0.00040+3.0)*6
pdraw particle emitter point_cyan motion y 2.5
pdraw particle emitter point_cyan motion z 10+cos(t*0.00040+3.0)*3
pdraw particle emitter point_cyan appearance style soft_dot
pdraw particle emitter point_cyan appearance color #40ffff
pdraw particle emitter point_cyan appearance size_world 0.3,0.3
pdraw particle emitter point_cyan appearance glowing true
pdraw particle emitter point_cyan appearance light 15
pdraw particle emitter point_cyan appearance light_range 12
pdraw particle emitter point_cyan appearance light_falloff constant
pdraw particle emitter point_cyan lifetime -1
pdraw particle emitter point_cyan rate 1
pdraw particle emitter point_cyan max_alive 1
pdraw particle emitter point_cyan start

pdraw particle emitter reset ref_white
pdraw particle emitter ref_white anchor fixed ~4 ~3 ~11
pdraw particle emitter ref_white sample count 1
pdraw particle emitter ref_white birth position vec3(0,0,0)
pdraw particle emitter ref_white appearance style soft_dot
pdraw particle emitter ref_white appearance color #ffffff
pdraw particle emitter ref_white appearance size_world 0.3,0.3
pdraw particle emitter ref_white appearance glowing true
pdraw particle emitter ref_white appearance light 12
pdraw particle emitter ref_white appearance light_range 10
pdraw particle emitter ref_white appearance light_falloff smooth
pdraw particle emitter ref_white lifetime -1
pdraw particle emitter ref_white rate 1
pdraw particle emitter ref_white max_alive 1
pdraw particle emitter ref_white start
