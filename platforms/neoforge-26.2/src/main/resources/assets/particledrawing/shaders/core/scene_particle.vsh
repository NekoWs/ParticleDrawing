#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in ivec2 UV2;
in ivec2 UV1;

uniform sampler2D Sampler2;
out vec2 distances;
out vec2 texCoord;
out vec4 vertexColor;
flat out vec2 material;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    distances = vec2(fog_spherical_distance(Position), fog_cylindrical_distance(Position));
    texCoord = UV0;
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    material = vec2(UV1) / 256.0;
}
