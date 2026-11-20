struct PDParticle {
    vec4 positionWidth;
    vec4 velocityHeight;
    vec4 accelerationBirth;
    vec4 color;
    vec4 uv;
};

layout(std140) uniform PDInstances {
    PDParticle pdParticles[192];
};

layout(std140) uniform PDFrame {
    vec4 pdOriginTick;
    vec4 pdLifetimeStopPartial;
};

bool pdSampleParticle(out vec3 position, out vec2 size, out vec4 color, out vec2 uv, vec2 corner) {
    PDParticle particle = pdParticles[PD_INSTANCE_INDEX];
    position = pdOriginTick.xyz + particle.positionWidth.xyz;
    size = vec2(particle.positionWidth.w, particle.velocityHeight.w);
    color = particle.color;
    uv = mix(particle.uv.xy, particle.uv.zw, vec2(corner.x, -corner.y) * 0.5 + 0.5);
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
    }
    return color.a > 0.0 && size.x > 0.0 && size.y > 0.0;
}
