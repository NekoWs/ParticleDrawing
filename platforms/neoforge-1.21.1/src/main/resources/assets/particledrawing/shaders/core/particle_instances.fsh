#version 150

#moj_import <fog.glsl>
uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform sampler2D Sampler0;
in float distances;
in vec2 texCoord;
in vec4 vertexColor;
out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord) * vertexColor * ColorModulator;
    if (color.a < 0.001) discard;
    fragColor = linear_fog(color, distances, FogStart, FogEnd, FogColor);
}
