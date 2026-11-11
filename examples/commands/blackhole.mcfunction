# 黑洞吸积：三层壳共两万颗。出生给切向速度形成轨道，速度每刻轻微阻尼并带一点向内分量，
# 再叠 1/r² 向心加速度让轨道略微内旋；落进 1.8 格的事件视界即被吞掉，由持续发射补位。
# 相机站在 (0,116,62) 朝原点看能看全整个盘；把三处 anchor 换成实体即可跟随。
pdraw particle emitter reset hole_a
pdraw particle emitter hole_a anchor fixed ~ ~ ~
pdraw particle emitter hole_a sample count 6667
pdraw particle emitter hole_a let ang u*6.2832
pdraw particle emitter hole_a let rad 8+16*fract(u*7.31)
pdraw particle emitter hole_a let k 1.06
pdraw particle emitter hole_a let v0 0.25
pdraw particle emitter hole_a birth position vec3(cos(ang+0)*rad, (fract(u*3.17)-0.5)*7, sin(ang+0)*rad)
pdraw particle emitter hole_a birth velocity vec3(-sin(ang+0)*v0, 0, cos(ang+0)*v0)
pdraw particle emitter hole_a birth r 0.55+0.45*(1-(rad-8)/16)
pdraw particle emitter hole_a birth g 0.25+0.5*(rad-8)/16
pdraw particle emitter hole_a birth b 0.9
pdraw particle emitter hole_a motion velocity vec3(vx*0.985-x*0.008, vy*0.985-y*0.06, vz*0.985-z*0.008)
pdraw particle emitter hole_a motion acceleration vec3(-x*k/((x*x+y*y+z*z)*sqrt(x*x+y*y+z*z)), -y*k*6/((x*x+y*y+z*z)*sqrt(x*x+y*y+z*z)), -z*k/((x*x+y*y+z*z)*sqrt(x*x+y*y+z*z)))
pdraw particle emitter hole_a kill x*x+y*y+z*z < 3.24
pdraw particle emitter hole_a appearance size_world 0.16,0.16
pdraw particle emitter hole_a appearance glowing true
pdraw particle emitter hole_a appearance light 12
pdraw particle emitter hole_a lifetime -1
pdraw particle emitter hole_a max_alive 6667
pdraw particle emitter hole_a rate 512
pdraw particle emitter hole_a start
pdraw particle emitter reset hole_b
pdraw particle emitter hole_b anchor fixed ~ ~ ~
pdraw particle emitter hole_b sample count 6667
pdraw particle emitter hole_b let ang u*6.2832
pdraw particle emitter hole_b let rad 8+16*fract(u*7.31)
pdraw particle emitter hole_b let k 1.06
pdraw particle emitter hole_b let v0 0.25
pdraw particle emitter hole_b birth position vec3(cos(ang+2.094)*rad, (fract(u*3.17)-0.5)*7, sin(ang+2.094)*rad)
pdraw particle emitter hole_b birth velocity vec3(-sin(ang+2.094)*v0, 0, cos(ang+2.094)*v0)
pdraw particle emitter hole_b birth r 0.55+0.45*(1-(rad-8)/16)
pdraw particle emitter hole_b birth g 0.25+0.5*(rad-8)/16
pdraw particle emitter hole_b birth b 0.9
pdraw particle emitter hole_b motion velocity vec3(vx*0.985-x*0.008, vy*0.985-y*0.06, vz*0.985-z*0.008)
pdraw particle emitter hole_b motion acceleration vec3(-x*k/((x*x+y*y+z*z)*sqrt(x*x+y*y+z*z)), -y*k*6/((x*x+y*y+z*z)*sqrt(x*x+y*y+z*z)), -z*k/((x*x+y*y+z*z)*sqrt(x*x+y*y+z*z)))
pdraw particle emitter hole_b kill x*x+y*y+z*z < 3.24
pdraw particle emitter hole_b appearance size_world 0.16,0.16
pdraw particle emitter hole_b appearance glowing true
pdraw particle emitter hole_b appearance light 12
pdraw particle emitter hole_b lifetime -1
pdraw particle emitter hole_b max_alive 6667
pdraw particle emitter hole_b rate 512
pdraw particle emitter hole_b start
pdraw particle emitter reset hole_c
pdraw particle emitter hole_c anchor fixed ~ ~ ~
pdraw particle emitter hole_c sample count 6667
pdraw particle emitter hole_c let ang u*6.2832
pdraw particle emitter hole_c let rad 8+16*fract(u*7.31)
pdraw particle emitter hole_c let k 1.06
pdraw particle emitter hole_c let v0 0.25
pdraw particle emitter hole_c birth position vec3(cos(ang+4.189)*rad, (fract(u*3.17)-0.5)*7, sin(ang+4.189)*rad)
pdraw particle emitter hole_c birth velocity vec3(-sin(ang+4.189)*v0, 0, cos(ang+4.189)*v0)
pdraw particle emitter hole_c birth r 0.55+0.45*(1-(rad-8)/16)
pdraw particle emitter hole_c birth g 0.25+0.5*(rad-8)/16
pdraw particle emitter hole_c birth b 0.9
pdraw particle emitter hole_c motion velocity vec3(vx*0.985-x*0.008, vy*0.985-y*0.06, vz*0.985-z*0.008)
pdraw particle emitter hole_c motion acceleration vec3(-x*k/((x*x+y*y+z*z)*sqrt(x*x+y*y+z*z)), -y*k*6/((x*x+y*y+z*z)*sqrt(x*x+y*y+z*z)), -z*k/((x*x+y*y+z*z)*sqrt(x*x+y*y+z*z)))
pdraw particle emitter hole_c kill x*x+y*y+z*z < 3.24
pdraw particle emitter hole_c appearance size_world 0.16,0.16
pdraw particle emitter hole_c appearance glowing true
pdraw particle emitter hole_c appearance light 12
pdraw particle emitter hole_c lifetime -1
pdraw particle emitter hole_c max_alive 6667
pdraw particle emitter hole_c rate 512
pdraw particle emitter hole_c start