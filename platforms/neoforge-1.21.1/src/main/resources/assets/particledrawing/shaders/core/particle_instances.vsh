#version 150

#moj_import <fog.glsl>
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
#define PD_INSTANCE_INDEX gl_InstanceID
#moj_import <particledrawing:particle_instances.glsl>

uniform sampler2D Sampler2;
in vec3 Corner;
in vec3 FaceNormal;
in vec2 ModelUV;
out float distances;
out vec2 texCoord;
out vec4 vertexColor;

void main() {
    vec3 position;
    vec2 size;
    vec4 rotation;
    int light;
    if (!pdSampleParticle(position, size, vertexColor, texCoord, rotation, light, ModelUV)) {
        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
        distances = 0.0;
        return;
    }
    vec4 view = pdVertex(ModelViewMat, position, size, rotation, Corner, FaceNormal, vertexColor);
    if (light >= 0) vertexColor *= texelFetch(Sampler2, pdPackedLight(light) / 16, 0);
    gl_Position = ProjMat * view;
    distances = fog_distance(position, FogShape);
}
