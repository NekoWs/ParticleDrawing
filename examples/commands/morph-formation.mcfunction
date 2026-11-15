# 持久粒子群的编队变换：圆环 → 螺旋塔，无限往返。
# 粒子全程不重生不销毁，只是被平滑地重新分配到新槽位。
pdraw particle group reset morphdemo 0 100 0
pdraw particle add morphdemo circle 0 100 0 3 120 glowing=true size_world=0.18,0.18

pdraw particle program morphdemo morph slots 120 vec3(cos(u*6.2832)*(0.6+2.4*u), 100.2+u*3.4, sin(u*6.2832)*(0.6+2.4*u))
pdraw particle program morphdemo morph ticks 120
pdraw particle program morphdemo morph easing in_out_cubic
pdraw particle program morphdemo morph max_speed 0.5
pdraw particle program morphdemo morph loop -1
pdraw particle program morphdemo morph start