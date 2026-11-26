#version 150

#moj_import <fog.glsl>
#moj_import <particledrawing:scene_particle_sample.glsl>

uniform sampler2D Sampler0;
uniform mat4 ProjMat;
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in float vertexDistance;
in vec2 texCoord;
in vec4 vertexColor;
flat in vec2 material;
out vec4 fragColor;

void main() {
    vec4 mask = texture(Sampler0, texCoord);
    vec4 color = mask * vertexColor * ColorModulator;
    color = sampleParticleScene(mask, color, material, ProjMat, false);
    fragColor = material.y > 0.0 ? color : linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
