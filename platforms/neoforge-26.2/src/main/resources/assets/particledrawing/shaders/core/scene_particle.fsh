#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <particledrawing:scene_particle_sample.glsl>

uniform sampler2D Sampler0;
in vec2 distances;
in vec2 texCoord;
in vec4 vertexColor;
flat in vec2 material;
out vec4 fragColor;

void main() {
    vec4 mask = texture(Sampler0, texCoord);
#if PD_ZERO_TO_ONE
    const bool zeroToOne = true;
#else
    const bool zeroToOne = false;
#endif
    vec4 color = mask * vertexColor * ColorModulator;
    color = sampleParticleScene(mask, color, material, ProjMat, zeroToOne, true);
    fragColor = material.y > 0.0 ? color : apply_fog(color, distances.x, distances.y,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
