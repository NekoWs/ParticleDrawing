#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#define PD_INSTANCE_INDEX gl_InstanceIndex
#include <particledrawing:particle_instances.glsl>

#include <minecraft:sample_lightmap.glsl>
uniform sampler2D Sampler2;
layout(location = 0) in vec3 Corner;
layout(location = 1) in vec3 FaceNormal;
layout(location = 2) in vec2 ModelUV;
layout(location = 0) out vec2 distances;
layout(location = 1) out vec2 texCoord;
layout(location = 2) out vec4 vertexColor;

void main() {
    vec3 position;
    vec2 size;
    vec4 rotation;
    int light;
    if (!pdSampleParticle(position, size, vertexColor, texCoord, rotation, light, ModelUV)) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        distances = vec2(0.0);
        return;
    }
    vec4 view = pdIsStrand() ? ModelViewMat * vec4(position, 1.0)
                              : pdVertex(ModelViewMat, position, size, rotation, Corner, FaceNormal, vertexColor);
    if (light >= 0) vertexColor *= sample_lightmap(Sampler2, pdPackedLight(light));
    gl_Position = ProjMat * view;
    distances = vec2(fog_spherical_distance(position), fog_cylindrical_distance(position));
}
