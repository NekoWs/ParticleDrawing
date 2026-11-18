# 在有屋顶与露天地面的交界处运行。命中实体方块或液面后才溅起水花。
pdraw particle emitter reset rain_splash
pdraw particle emitter rain_splash sample count 9
pdraw particle emitter rain_splash lifetime 14
pdraw particle emitter rain_splash appearance style soft_dot
pdraw particle emitter rain_splash appearance color #79cfff
pdraw particle emitter rain_splash appearance size_world 0.10,0.10
pdraw particle emitter rain_splash appearance glowing true
pdraw particle emitter rain_splash let ang i*2.39996323
pdraw particle emitter rain_splash birth velocity vec3(event_nx*0.15+cos(ang)*0.14,event_ny*0.22,sin(ang)*0.14+event_nz*0.15)
pdraw particle emitter rain_splash motion ay -0.045
pdraw particle emitter rain_splash motion a 1-progress

pdraw particle emitter reset rain_contact
pdraw particle emitter rain_contact anchor fixed ~ ~4 ~
pdraw particle emitter rain_contact sample count 81
pdraw particle emitter rain_contact birth position vec3(i%9*0.48-1.92,0,floor(i%81/9)*0.48-1.92)
pdraw particle emitter rain_contact birth vy -0.34
pdraw particle emitter rain_contact where world_loaded && open_sky
pdraw particle emitter rain_contact motion ay -0.08
pdraw particle emitter rain_contact appearance style soft_dot
pdraw particle emitter rain_contact appearance color #2a9eff
pdraw particle emitter rain_contact appearance size_world 0.09,0.37
pdraw particle emitter rain_contact appearance glowing true
pdraw particle emitter rain_contact appearance align_velocity true
pdraw particle emitter rain_contact lifetime 30
pdraw particle emitter rain_contact rate 6
pdraw particle emitter rain_contact collide both
pdraw particle emitter rain_contact on contact rain_splash remove_parent
pdraw particle emitter rain_contact burst
