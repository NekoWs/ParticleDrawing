# 黑洞吸积：三层壳共两万颗，向心加速度 + 切向旋进，落进事件视界即被吞掉。
# 持续发射（start），粒子被吞掉后自动补位，所以整场是稳定的吸积盘。
# 相机站在 (0,116,62) 朝原点看能看全整个盘；把三处 anchor 换成实体即可跟随。
pdraw particle emitter reset hole_a
pdraw particle emitter reset hole_b
pdraw particle emitter reset hole_c
pdraw particle emitter hole_a anchor fixed ~ ~ ~
pdraw particle emitter hole_b anchor fixed ~ ~ ~
pdraw particle emitter hole_c anchor fixed ~ ~ ~
pdraw particle emitter hole_a sample count 6667
pdraw particle emitter hole_b sample count 6667
pdraw particle emitter hole_c sample count 6667
pdraw particle emitter hole_a let ang u*6.2832
pdraw particle emitter hole_b let ang u*6.2832
pdraw particle emitter hole_c let ang u*6.2832
# 半径按 u 打散成 8..24 格的三层壳，外层更冷更暗
pdraw particle emitter hole_a let rad 8+16*fract(u*7.31)
pdraw particle emitter hole_b let rad 8+16*fract(u*7.31)
pdraw particle emitter hole_c let rad 8+16*fract(u*7.31)
pdraw particle emitter hole_a let k 26
pdraw particle emitter hole_b let k 26
pdraw particle emitter hole_c let k 26
pdraw particle emitter hole_a let s 1.7
pdraw particle emitter hole_b let s 1.7
pdraw particle emitter hole_c let s 1.7
pdraw particle emitter hole_a birth position vec3(cos(ang)*rad, (fract(u*3.17)-0.5)*7, sin(ang)*rad)
pdraw particle emitter hole_b birth position vec3(cos(ang+2.094)*rad, (fract(u*3.17)-0.5)*7, sin(ang+2.094)*rad)
pdraw particle emitter hole_c birth position vec3(cos(ang+4.189)*rad, (fract(u*3.17)-0.5)*7, sin(ang+4.189)*rad)
pdraw particle emitter hole_a birth r 0.55+0.45*(1-(rad-8)/16)
pdraw particle emitter hole_b birth r 0.45+0.55*(1-(rad-8)/16)
pdraw particle emitter hole_c birth r 0.35+0.65*(1-(rad-8)/16)
pdraw particle emitter hole_a birth g 0.25+0.5*(rad-8)/16
pdraw particle emitter hole_b birth g 0.3+0.5*(rad-8)/16
pdraw particle emitter hole_c birth g 0.35+0.5*(rad-8)/16
pdraw particle emitter hole_a birth b 0.9
pdraw particle emitter hole_b birth b 0.75
pdraw particle emitter hole_c birth b 0.6
# 向心加速度按 1/r² 落，再叠一个绕 Y 轴的切向分量，粒子才会旋着掉进去而不是直落。
# 只用 acceleration：速度由引擎逐刻积分，位置由速度推进，不需要自己写 position。
pdraw particle emitter hole_a motion acceleration vec3(-x*k/((x*x+y*y+z*z)*sqrt(x*x+y*y+z*z))-z*s/sqrt(x*x+y*y+z*z), -y*k*4/((x*x+y*y+z*z)*sqrt(x*x+y*y+z*z)), -z*k/((x*x+y*y+z*z)*sqrt(x*x+y*y+z*z))+x*s/sqrt(x*x+y*y+z*z))
pdraw particle emitter hole_b motion acceleration vec3(-x*k/((x*x+y*y+z*z)*sqrt(x*x+y*y+z*z))-z*s/sqrt(x*x+y*y+z*z), -y*k*4/((x*x+y*y+z*z)*sqrt(x*x+y*y+z*z)), -z*k/((x*x+y*y+z*z)*sqrt(x*x+y*y+z*z))+x*s/sqrt(x*x+y*y+z*z))
pdraw particle emitter hole_c motion acceleration vec3(-x*k/((x*x+y*y+z*z)*sqrt(x*x+y*y+z*z))-z*s/sqrt(x*x+y*y+z*z), -y*k*4/((x*x+y*y+z*z)*sqrt(x*x+y*y+z*z)), -z*k/((x*x+y*y+z*z)*sqrt(x*x+y*y+z*z))+x*s/sqrt(x*x+y*y+z*z))
# 半径小于 1.8 格即被吞没；kill 里的比较走热路径
pdraw particle emitter hole_a kill x*x+y*y+z*z < 3.24
pdraw particle emitter hole_b kill x*x+y*y+z*z < 3.24
pdraw particle emitter hole_c kill x*x+y*y+z*z < 3.24
pdraw particle emitter hole_a appearance size_world 0.16,0.16
pdraw particle emitter hole_b appearance size_world 0.16,0.16
pdraw particle emitter hole_c appearance size_world 0.16,0.16
pdraw particle emitter hole_a appearance glowing true
pdraw particle emitter hole_b appearance glowing true
pdraw particle emitter hole_c appearance glowing true
pdraw particle emitter hole_a appearance light 12
pdraw particle emitter hole_b appearance light 12
pdraw particle emitter hole_c appearance light 12
pdraw particle emitter hole_a lifetime -1
pdraw particle emitter hole_b lifetime -1
pdraw particle emitter hole_c lifetime -1
pdraw particle emitter hole_a max_alive 6667
pdraw particle emitter hole_b max_alive 6667
pdraw particle emitter hole_c max_alive 6667
pdraw particle emitter hole_a rate 512
pdraw particle emitter hole_b rate 512
pdraw particle emitter hole_c rate 512
pdraw particle emitter hole_a start
pdraw particle emitter hole_b start
pdraw particle emitter hole_c start
