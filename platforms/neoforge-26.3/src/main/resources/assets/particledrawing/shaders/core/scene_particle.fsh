#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <particledrawing:scene_particle_sample.glsl>

uniform sampler2D Sampler0;
layout(location = 0) in vec2 distances;
layout(location = 1) in vec2 texCoord;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) flat in vec2 material;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 mask = texture(Sampler0, texCoord);
#ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    const bool zeroToOne = true;
#else
    const bool zeroToOne = false;
#endif
    vec4 color = mask * vertexColor * ColorModulator;
    color = sampleParticleScene(mask, color, material, ProjMat, zeroToOne, true);
    fragColor = material.y > 0.0 ? color : apply_fog(color, distances.x, distances.y,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
