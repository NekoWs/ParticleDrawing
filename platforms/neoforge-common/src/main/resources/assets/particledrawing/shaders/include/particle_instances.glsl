struct PDParticle {
    vec4 positionWidth;
    vec4 velocityHeight;
    vec4 accelerationBirth;
    vec4 color;
    vec4 uv;
    vec4 colorEnd;
    vec4 orientation;
};

layout(std140) uniform PDInstances {
    PDParticle pdParticles[128];
};

layout(std140) uniform PDFrame {
    vec4 pdOriginTick;
    vec4 pdLifetimeStopPartial;
    ivec4 pdOrder[32];
};

vec3 pdRotate(vec4 q, vec3 v) {
    return v + 2.0 * cross(q.xyz, cross(q.xyz, v) + q.w * v);
}

bool pdSampleParticle(out vec3 position, out vec2 size, out vec4 color, out vec2 uv,
                      out vec4 rotation, out int light, vec2 cornerUv) {
    int slot = PD_INSTANCE_INDEX;
    if (pdLifetimeStopPartial.w > 0.0) slot = pdOrder[slot / 4][slot % 4];
    PDParticle particle = pdParticles[slot];
    position = pdOriginTick.xyz + particle.positionWidth.xyz;
    size = vec2(particle.positionWidth.w, particle.velocityHeight.w);
    color = particle.color;
    uv = mix(particle.uv.xy, particle.uv.zw, cornerUv);
    rotation = particle.orientation;
    light = -1;
    if (pdOriginTick.w >= 0.0) {
        float birth = particle.accelerationBirth.w;
        float age = pdOriginTick.w - birth;
        if (age < 0.0 || age >= pdLifetimeStopPartial.x || birth > pdLifetimeStopPartial.y) return false;
        // 相邻两个离散状态按渲染帧插值。每刻先加速，再按新速度位移。
        float previous = max(age - 1.0, 0.0);
        vec3 velocity = particle.velocityHeight.xyz;
        vec3 acceleration = particle.accelerationBirth.xyz;
        position += velocity * previous + acceleration * (previous * (previous + 1.0) * 0.5);
        if (age > 0.0) position += (velocity + acceleration * age) * pdLifetimeStopPartial.z;
    } else {
        float partial = pdLifetimeStopPartial.z;
        position += particle.velocityHeight.xyz * partial;
        size = mix(size, particle.accelerationBirth.xy, partial);
        color = mix(particle.color, particle.colorEnd, partial);
        light = int(particle.accelerationBirth.z);
    }
    return color.a > 0.0 && size.x > 0.0 && size.y > 0.0;
}

vec4 pdVertex(mat4 view, vec3 position, vec2 size, vec4 rotation, vec3 corner, vec3 faceNormal, inout vec4 color) {
    vec3 local = corner * vec3(size.x, size.y, size.y);
    bool billboard = dot(rotation, rotation) == 0.0;
    vec3 normal = billboard ? transpose(mat3(view)) * faceNormal : pdRotate(rotation, faceNormal);
    if (dot(faceNormal, faceNormal) > 0.0) color.rgb *= clamp(0.70 + 0.28 * normal.y + 0.10 * normal.x, 0.32, 1.0);
    if (billboard) return view * vec4(position, 1.0) + vec4(local, 0.0);
    return view * vec4(position + pdRotate(rotation, local), 1.0);
}

ivec2 pdPackedLight(int light) {
    return ivec2(light & 65535, light >> 16);
}
