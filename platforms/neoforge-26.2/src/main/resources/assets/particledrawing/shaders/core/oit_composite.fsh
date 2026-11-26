#version 330

#moj_import <particledrawing:particle_oit.glsl>

uniform sampler2D InSampler;
out vec4 fragColor;

void main() {
    fragColor = pd_resolve_transparency(texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0));
}
