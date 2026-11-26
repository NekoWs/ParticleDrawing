#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#define PD_INSTANCE_INDEX gl_InstanceID
#moj_import <particledrawing:particle_instances.glsl>

#moj_import <minecraft:sample_lightmap.glsl>
uniform sampler2D Sampler2;
in vec3 Corner;
in vec3 FaceNormal;
in vec2 ModelUV;
out vec2 distances;
out vec2 texCoord;
out vec4 vertexColor;

void main() {
    vec3 position;
    vec2 size;
    vec4 rotation;
    int light;
    if (!pdSampleParticle(position, size, vertexColor, texCoord, rotation, light, ModelUV)) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        distances = vec2(0.0);
        return;
    }
    vec4 view = pdIsStrand() ? ModelViewMat * vec4(position, 1.0)
                              : pdVertex(ModelViewMat, position, size, rotation, Corner, FaceNormal, vertexColor);
    if (light >= 0) vertexColor *= sample_lightmap(Sampler2, pdPackedLight(light));
    gl_Position = ProjMat * view;
    distances = vec2(fog_spherical_distance(position), fog_cylindrical_distance(position));
}
