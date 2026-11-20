#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#define PD_INSTANCE_INDEX gl_InstanceIndex
#include <particledrawing:particle_instances.glsl>

layout(location = 0) in vec3 Corner;
layout(location = 0) out vec2 distances;
layout(location = 1) out vec2 texCoord;
layout(location = 2) out vec4 vertexColor;

void main() {
    vec3 position;
    vec2 size;
    if (!pdSampleParticle(position, size, vertexColor, texCoord, Corner.xy)) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        distances = vec2(0.0);
        return;
    }
    vec4 view = ModelViewMat * vec4(position, 1.0);
    view.xy += Corner.xy * size;
    gl_Position = ProjMat * view;
    distances = vec2(fog_spherical_distance(position), fog_cylindrical_distance(position));
}
