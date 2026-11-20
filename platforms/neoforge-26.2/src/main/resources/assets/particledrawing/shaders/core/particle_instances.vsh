#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#define PD_INSTANCE_INDEX gl_InstanceID
#moj_import <particledrawing:particle_instances.glsl>

in vec3 Corner;
out vec2 distances;
out vec2 texCoord;
out vec4 vertexColor;

void main() {
    vec3 position;
    vec2 size;
    if (!pdSampleParticle(position, size, vertexColor, texCoord, Corner.xy)) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        distances = vec2(0.0);
        return;
    }
    vec4 view = ModelViewMat * vec4(position, 1.0);
    view.xy += Corner.xy * size;
    gl_Position = ProjMat * view;
    distances = vec2(fog_spherical_distance(position), fog_cylindrical_distance(position));
}
