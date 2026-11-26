#version 330
#extension GL_ARB_separate_shader_objects : require

#include <particledrawing:particle_oit.glsl>

uniform sampler2D InSampler;
layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = pd_resolve_transparency(texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0));
}
