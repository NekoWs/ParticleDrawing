#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
uniform sampler2D Sampler0;
layout(location = 0) in vec2 distances;
layout(location = 1) in vec2 texCoord;
layout(location = 2) in vec4 vertexColor;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord) * vertexColor * ColorModulator;
    if (color.a < 0.001) discard;
    fragColor = apply_fog(color, distances.x, distances.y,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
