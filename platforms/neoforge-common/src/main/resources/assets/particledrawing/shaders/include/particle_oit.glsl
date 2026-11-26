// Store optical depth rather than transmittance so one additive attachment suffices.
vec4 pd_accumulate_transparency(vec4 color) {
    float thickness = -log(max(1.0 - color.a, 1e-4)) / 16.0;
    return vec4(color.rgb * thickness, thickness);
}

vec4 pd_resolve_transparency(vec4 accumulated) {
    if (accumulated.a <= 0.0) discard;
    return vec4(accumulated.rgb / accumulated.a, 1.0 - exp(-16.0 * accumulated.a));
}
