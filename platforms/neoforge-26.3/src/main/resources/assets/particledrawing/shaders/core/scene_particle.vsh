#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 3) in ivec2 UV2;
layout(location = 4) in ivec2 UV1;

uniform sampler2D Sampler2;
layout(location = 0) out vec2 distances;
layout(location = 1) out vec2 texCoord;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) flat out vec2 material;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    distances = vec2(fog_spherical_distance(Position), fog_cylindrical_distance(Position));
    texCoord = UV0;
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    material = vec2(UV1) / 256.0;
}
