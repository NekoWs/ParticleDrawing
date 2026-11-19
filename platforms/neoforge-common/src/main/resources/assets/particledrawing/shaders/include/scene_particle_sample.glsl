#ifndef PARTICLEDRAWING_SCENE_PARTICLE_SAMPLE
#define PARTICLEDRAWING_SCENE_PARTICLE_SAMPLE

uniform sampler2D SceneDepth;
uniform sampler2D SceneColor;

float particleViewDepth(float depth, mat4 projection, bool zeroToOne) {
    float ndc = zeroToOne ? depth : depth * 2.0 - 1.0;
    return projection[3][2] / (ndc + projection[2][2]);
}

vec4 sampleParticleScene(vec4 mask, vec4 color, vec2 material, mat4 projection, bool zeroToOne, bool reversedDepth) {
    vec2 size = vec2(textureSize(SceneDepth, 0));
    vec2 uv = gl_FragCoord.xy / size;
    if (material.x > 0.0) {
        float sceneDepth = texture(SceneDepth, uv).r;
        float gap = particleViewDepth(sceneDepth, projection, zeroToOne)
            - particleViewDepth(gl_FragCoord.z, projection, zeroToOne);
        color.a *= smoothstep(0.0, material.x, gap);
    }
    if (color.a <= 0.003) discard;
    if (material.y > 0.0) {
        vec2 normal = mask.rg * 2.0 - 1.0;
        vec2 refracted = uv + normal * material.y * mask.a / size;
        float displacedDepth = texture(SceneDepth, refracted).r;
        bool foreground = reversedDepth ? displacedDepth > gl_FragCoord.z : displacedDepth < gl_FragCoord.z;
        color.rgb = texture(SceneColor, foreground ? uv : refracted).rgb;
    }
    return color;
}

#endif
