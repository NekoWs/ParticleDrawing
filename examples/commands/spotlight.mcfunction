# 三盏射灯照在脚前的地面上：锥角、半径、衰减各不相同，颜色分别偏红/绿/蓝。
# 在平坦地面执行；`light_only=true` 让粒子本身不可见，只投光。
# 想让它扫动，取消最后一行的注释（move 是相对位移，会画出一道移动的光斑）。
pdraw particle group reset lamps ~ ~3 ~
pdraw particle add lamps dot ~ ~3 ~ light=15 light_only=true color=#ff5938 spot=35,20 light_dir=0,-90 light_range=12 light_falloff=smooth
pdraw particle add lamps dot ~2 ~4 ~ light=15 light_only=true color=#59ff8c spot=20,8 light_dir=0,-90 light_range=18 light_falloff=inverse
pdraw particle add lamps dot ~-2 ~3 ~ light=15 light_only=true color=#598cff spot=55,30 light_dir=0,-90 light_range=8 light_falloff=constant
# pdraw particle program lamps move 3 0 0 100 linear