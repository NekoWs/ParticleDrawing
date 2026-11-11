# ColorBlock 锐角星形：参数拆成 p/q 两条 let，速度用矩阵一次写完。
pdraw particle emitter reset sharp-star
pdraw particle emitter sharp-star anchor fixed ~ ~4 ~
pdraw particle emitter sharp-star sample range -3082 3082 1
pdraw particle emitter sharp-star let amp 5
pdraw particle emitter sharp-star let p floor(u/78.5)/25
pdraw particle emitter sharp-star let q u%157/25
pdraw particle emitter sharp-star birth position vec3(amp*(cos(p)*cos(q))^3, amp*(sin(p)*cos(q))^3, amp*sin(q)^3)
pdraw particle emitter sharp-star birth r 0
pdraw particle emitter sharp-star birth g 1
pdraw particle emitter sharp-star birth b 1
pdraw particle emitter sharp-star let ang 0.05
pdraw particle emitter sharp-star let turn mat3(vec3(-sin(ang), 0, -cos(ang)), vec3(0, 1, 0), vec3(cos(ang), 0, -sin(ang)))
pdraw particle emitter sharp-star motion velocity turn*vec3(x*2*sin(ang), 0.1, z*2*sin(ang))
pdraw particle emitter sharp-star texture particle:minecraft:end_rod
pdraw particle emitter sharp-star appearance size_world 0.1,0.1
pdraw particle emitter sharp-star appearance glowing true
pdraw particle emitter sharp-star lifetime -1
pdraw particle emitter sharp-star max_alive 8192
pdraw particle emitter sharp-star rate 1024
pdraw particle emitter sharp-star burst
