#version 150

#moj_import <fog.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in ivec2 UV2;
in ivec2 UV1;

uniform sampler2D Sampler2;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;

out float vertexDistance;
out vec2 texCoord;
out vec4 vertexColor;
flat out vec2 material;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexDistance = fog_distance(Position, FogShape);
    texCoord = UV0;
    vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);
    material = vec2(UV1) / 256.0;
}
