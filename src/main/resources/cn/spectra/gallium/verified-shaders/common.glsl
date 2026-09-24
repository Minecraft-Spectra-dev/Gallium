vec4 finalizeGlow(float glowMask, float intensity, vec3 innerColor) {
    glowMask = smoothstep(0.005, 0.30, glowMask);
    float finalIntensity = glowMask * intensity * 1.5;
    return vec4(innerColor * finalIntensity, 0.5);
}
