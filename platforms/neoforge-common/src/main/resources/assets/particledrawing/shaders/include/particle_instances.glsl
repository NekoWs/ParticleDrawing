struct PDParticle {
    vec4 positionWidth;
    vec4 velocityHeight;
    vec4 accelerationBirth;
    vec4 color;
    vec4 uv;
    vec4 colorEnd;
    vec4 orientation;
    vec4 strandTangents;
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

bool pdIsStrand() {
    return pdOriginTick.w < -1.0;
}

vec4 pdStrandColor(float rg, float ba) {
    uint u = uint(rg);
    uint v = uint(ba);
    return vec4(u >> 8u, u & 255u, v >> 8u, v & 255u) / 255.0;
}

vec3 pdStrandTangent(float encoded) {
    uint bits = uint(encoded);
    return (vec3(bits >> 16u, (bits >> 8u) & 255u, bits & 255u) - 127.0) / 127.0;
}

vec3 pdStrandAcross(vec3 tangent, vec3 center) {
    vec3 across = cross(tangent, -center);
    if (dot(across, across) < 0.00000001) {
        across = cross(tangent, abs(tangent.y) < abs(tangent.x) ? vec3(0, 1, 0) : vec3(1, 0, 0));
    }
    float length2 = dot(across, across);
    return length2 > 0.0 ? across * inversesqrt(length2) : vec3(0.0);
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
    if (pdIsStrand()) {
        float t = pdLifetimeStopPartial.z;
        bool end = cornerUv.x > 0.5;
        vec3 a = position + particle.velocityHeight.xyz * t;
        vec3 b = pdOriginTick.xyz + particle.accelerationBirth.xyz + particle.color.xyz * t;
        if (dot(a - b, a - b) < 0.00000001) return false;
        int j = end ? 2 : 0;
        vec3 tangent = mix(pdStrandTangent(particle.strandTangents[j]), pdStrandTangent(particle.strandTangents[j + 1]), t);
        float halfWidth = end ? mix(particle.accelerationBirth.w, particle.color.w, t)
                              : mix(particle.positionWidth.w, particle.velocityHeight.w, t);
        position = end ? b : a;
        position += pdStrandAcross(tangent, position) * halfWidth * (2.0 * cornerUv.y - 1.0);
        color = mix(pdStrandColor(particle.colorEnd[j], particle.orientation[j]),
                    pdStrandColor(particle.colorEnd[j + 1], particle.orientation[j + 1]), t);
        uv = vec2(particle.uv.x, mix(particle.uv.y, particle.uv.z, cornerUv.y));
        return true;
    }
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
