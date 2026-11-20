#version 150

#moj_import <fog.glsl>
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
#define PD_INSTANCE_INDEX gl_InstanceID
#moj_import <particledrawing:particle_instances.glsl>

in vec3 Corner;
out float distances;
out vec2 texCoord;
out vec4 vertexColor;

void main() {
    vec3 position;
    vec2 size;
    if (!pdSampleParticle(position, size, vertexColor, texCoord, Corner.xy)) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        distances = 0.0;
        return;
    }
    vec4 view = ModelViewMat * vec4(position, 1.0);
    view.xy += Corner.xy * size;
    gl_Position = ProjMat * view;
    distances = fog_distance(position, FogShape);
}
