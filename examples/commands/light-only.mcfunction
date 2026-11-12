# 只发光不画外观：挂在实体上的常驻灯（腰灯 / 饰品灯）。
# 粒子本身不可见，只把光注入客户端光图；外观交给物品模型。
# 需要客户端开着动态光照（enableDynamicLights），光源预算在配置里（maxDynamicLights）。
pdraw particle emitter reset waist_light
pdraw particle emitter waist_light anchor entity @s 0 1.0 0 false
pdraw particle emitter waist_light sample count 1
pdraw particle emitter waist_light birth position vec3(0,0,0)
pdraw particle emitter waist_light appearance light 15
pdraw particle emitter waist_light appearance intensity 0.8
pdraw particle emitter waist_light appearance light_only true
pdraw particle emitter waist_light lifetime -1
pdraw particle emitter waist_light max_alive 1
pdraw particle emitter waist_light rate 1
pdraw particle emitter waist_light start

# 想让灯随呼吸变化，给光强加一条曲线（而不是改透明度）：
# pdraw particle emitter waist_light appearance light_curve 0:1,40:0.5:linear,80:1
#
# 关掉：
# pdraw particle emitter waist_light remove
