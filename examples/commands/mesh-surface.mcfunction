# 先把 mesh-surface.obj 放入工作目录的 particleMeshes/。
pdraw particle emitter reset mesh_surface
pdraw particle emitter mesh_surface anchor fixed ~ ~2 ~
pdraw particle emitter mesh_surface mesh file mesh-surface.obj
pdraw particle emitter mesh_surface mesh count 360
pdraw particle emitter mesh_surface mesh seed 42
pdraw particle emitter mesh_surface appearance style soft_dot
pdraw particle emitter mesh_surface appearance size_world 0.07,0.07
pdraw particle emitter mesh_surface appearance color #d1a8ff
pdraw particle emitter mesh_surface appearance glowing true
pdraw particle emitter mesh_surface motion velocity vec3(sample_nx*0.03,sample_ny*0.03,sample_nz*0.03)
pdraw particle emitter mesh_surface motion a 1-progress
pdraw particle emitter mesh_surface rate 360
pdraw particle emitter mesh_surface lifetime 48
pdraw particle emitter mesh_surface burst
