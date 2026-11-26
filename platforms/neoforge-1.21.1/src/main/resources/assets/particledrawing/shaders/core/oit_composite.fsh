#version 150

#moj_import <particledrawing:particle_oit.glsl>

uniform sampler2D Sampler0;
out vec4 fragColor;

void main() {
    fragColor = pd_resolve_transparency(texelFetch(Sampler0, ivec2(gl_FragCoord.xy), 0));
}
