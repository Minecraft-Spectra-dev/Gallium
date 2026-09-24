#version 450
#moj_import <gallium:glow_common.glsl>

uniform sampler2D DiffuseSampler;
uniform sampler2D MaskSampler;
uniform sampler2D MaskDepthSampler;
uniform sampler2D SceneDepthSampler;

layout(std140) uniform GlowUniforms {
    float FrameTimeCounter;
    vec2 ScreenSize;
    float Intensity;
    float PulseSpeed;
    float WaveSpeed;
    vec3 InnerColor;
    vec3 OuterColor;
    vec4 ShaderAlign;
    vec4 ShaderOffset;
    float GalliumItemDistance;
    vec2 GalliumWorldToUv;
};

in vec2 texCoord;
out vec4 fragColor;

struct MaskGrid {
    ivec2 activeSize;
    ivec2 regionOrigin;
    ivec2 regionEnd;
    ivec2 baseCoord;
    vec2 fraction;
    bool valid;
};

ivec2 scaledTextureSize(sampler2D tex, vec2 scale) {
    return max(ivec2(round(vec2(textureSize(tex, 0)) * scale)), ivec2(1));
}

ivec2 activeRegionOrigin(ivec2 physicalSize, ivec2 activeSize, vec2 uvOffset) {
    // ShaderOffset contains an integer-pixel viewport origin plus a sub-pixel temporal offset.
    // Recover the stable region origin by rounding to the nearest pixel; keep the fractional
    // remainder in the sample coordinate below so jitter still moves this frame's lookup.
    ivec2 maxOrigin = max(physicalSize - activeSize, ivec2(0));
    return clamp(ivec2(round(uvOffset * vec2(physicalSize))), ivec2(0), maxOrigin);
}

ivec2 activeTexel(vec2 uv, sampler2D tex, vec2 scale, vec2 uvOffset, out bool valid) {
    ivec2 physicalSize = textureSize(tex, 0);
    ivec2 activeSize = min(scaledTextureSize(tex, scale), physicalSize);
    ivec2 regionOrigin = activeRegionOrigin(physicalSize, activeSize, uvOffset);
    ivec2 regionEnd = min(regionOrigin + activeSize, physicalSize);
    vec2 texel = uv * vec2(activeSize) + uvOffset * vec2(physicalSize);
    valid = all(greaterThanEqual(uv, vec2(0.0))) &&
            all(lessThan(uv, vec2(1.0))) &&
            all(greaterThanEqual(texel, vec2(regionOrigin))) &&
            all(lessThan(texel, vec2(regionEnd))) &&
            all(greaterThanEqual(texel, vec2(0.0))) &&
            all(lessThan(texel, vec2(physicalSize)));
    if (!valid) return regionOrigin;
    return clamp(ivec2(floor(texel)), regionOrigin, regionEnd - 1);
}

vec2 maskUvOffset() {
    return vec2(ShaderOffset.x, ShaderOffset.z);
}

vec2 sceneUvOffset() {
    return vec2(ShaderOffset.y, ShaderOffset.w);
}

MaskGrid maskGrid(vec2 uv) {
    ivec2 physicalSize = textureSize(MaskSampler, 0);
    ivec2 activeSize = min(scaledTextureSize(MaskSampler,
            vec2(ShaderAlign.x, ShaderAlign.z)), physicalSize);
    ivec2 regionOrigin = activeRegionOrigin(physicalSize, activeSize, maskUvOffset());
    ivec2 regionEnd = min(regionOrigin + activeSize, physicalSize);
    vec2 texelCenter = uv * vec2(activeSize)
            + maskUvOffset() * vec2(physicalSize) - vec2(0.5);
    bool valid = all(greaterThanEqual(uv, vec2(0.0)))
            && all(lessThan(uv, vec2(1.0)))
            && all(greaterThan(texelCenter, vec2(regionOrigin) - vec2(1.0)))
            && all(lessThan(texelCenter, vec2(regionEnd)))
            && all(greaterThan(texelCenter, vec2(-1.0)))
            && all(lessThan(texelCenter, vec2(physicalSize)));
    ivec2 baseCoord = ivec2(floor(texelCenter));
    return MaskGrid(activeSize, regionOrigin, regionEnd,
            baseCoord, fract(texelCenter), valid);
}

float sceneDepthAt(vec2 uv, bool exactReplay, out bool valid);

float visibleMaskTap(ivec2 coord, ivec2 activeSize,
        ivec2 regionOrigin, ivec2 regionEnd,
        float destinationDepth, bool compareDepth) {
    if (any(lessThan(coord, regionOrigin)) || any(greaterThanEqual(coord, regionEnd))) return 0.0;
    float alpha = texelFetch(MaskSampler, coord, 0).a;
    if (alpha <= 0.01) return 0.0;
    if (compareDepth) {
        float sourceDepth = texelFetch(MaskDepthSampler, coord, 0).r;
        vec2 sourceUv = (vec2(coord) + vec2(0.5)
                - maskUvOffset() * vec2(textureSize(MaskSampler, 0))) / vec2(activeSize);
        bool sourceSceneValid;
        float sourceSceneDepth = sceneDepthAt(
                sourceUv, ShaderAlign.w >= 0.0, sourceSceneValid);
        if (!sourceSceneValid || sourceDepth > min(destinationDepth, sourceSceneDepth)) return 0.0;
    }
    return 1.0;
}

float reconstructedMask(vec2 uv, float destinationDepth, bool compareDepth) {
    MaskGrid grid = maskGrid(uv);
    if (!grid.valid) return 0.0;

    vec2 inverseFraction = vec2(1.0) - grid.fraction;
    float result = 0.0;
    result += visibleMaskTap(grid.baseCoord, grid.activeSize,
            grid.regionOrigin, grid.regionEnd,
            destinationDepth, compareDepth) * inverseFraction.x * inverseFraction.y;
    result += visibleMaskTap(grid.baseCoord + ivec2(1, 0), grid.activeSize,
            grid.regionOrigin, grid.regionEnd,
            destinationDepth, compareDepth) * grid.fraction.x * inverseFraction.y;
    result += visibleMaskTap(grid.baseCoord + ivec2(0, 1), grid.activeSize,
            grid.regionOrigin, grid.regionEnd,
            destinationDepth, compareDepth) * inverseFraction.x * grid.fraction.y;
    result += visibleMaskTap(grid.baseCoord + ivec2(1, 1), grid.activeSize,
            grid.regionOrigin, grid.regionEnd,
            destinationDepth, compareDepth) * grid.fraction.x * grid.fraction.y;
    return result;
}

float sceneDepthAt(vec2 uv, bool exactReplay, out bool valid) {
    ivec2 coord = activeTexel(uv, SceneDepthSampler,
            vec2(ShaderAlign.y, abs(ShaderAlign.w)), sceneUvOffset(), valid);
    if (!valid) return 1.0;
    float sceneDepth = texelFetch(SceneDepthSampler, coord, 0).r;
    if (!exactReplay) {
        ivec2 physicalSize = textureSize(SceneDepthSampler, 0);
        ivec2 size = min(scaledTextureSize(SceneDepthSampler,
                vec2(ShaderAlign.y, abs(ShaderAlign.w))), physicalSize);
        ivec2 regionOrigin = activeRegionOrigin(
                physicalSize, size, sceneUvOffset());
        ivec2 regionEnd = min(regionOrigin + size, physicalSize);
        float maxSceneDepth = sceneDepth;
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                ivec2 neighbour = clamp(
                        coord + ivec2(x, y), regionOrigin, regionEnd - 1);
                maxSceneDepth = max(maxSceneDepth,
                        texelFetch(SceneDepthSampler, neighbour, 0).r);
            }
        }
        sceneDepth = maxSceneDepth;
    }
    return sceneDepth;
}

float isItem(vec2 uv) {
    return reconstructedMask(uv, 1.0, false);
}

float destinationDepthAt(vec2 uv, out bool valid) {
    ivec2 destinationCoord = activeTexel(uv, MaskDepthSampler,
            vec2(ShaderAlign.x, ShaderAlign.z), maskUvOffset(), valid);
    if (!valid) return 1.0;
    float destinationMaskDepth = texelFetch(MaskDepthSampler, destinationCoord, 0).r;
    bool sceneValid;
    float destinationSceneDepth = sceneDepthAt(uv, ShaderAlign.w >= 0.0, sceneValid);
    if (!sceneValid) {
        valid = false;
        return 1.0;
    }
    return min(destinationMaskDepth, destinationSceneDepth);
}

float outlineSample(vec2 sourceUv, float destinationDepth) {
    return reconstructedMask(sourceUv, destinationDepth, true);
}

void main() {
    vec2 texelSize = 1.0 / ScreenSize;
    // Fixed world thickness, projected with the item. At 1080p / 70 degree FOV
    // this is about 6 pixels at 2 blocks, 3 at 4 blocks, and 1.5 at 8 blocks.
    // Hands and unavailable projection metadata retain the original six pixels.
    const float WORLD_RADIUS = 1.0 / 64.0;
    vec2 radiusUv = texelSize * 6.0;
    float subpixelCoverage = 1.0;
    if (all(greaterThan(GalliumWorldToUv, vec2(0.0)))) {
        radiusUv = GalliumWorldToUv * WORLD_RADIUS;
        // No one-pixel floor: fractional outlines lose coverage as they shrink.
        // Without this, the reconstructed mask's fractional edge can retain a
        // bright fringe even when the geometric expansion approaches zero.
        subpixelCoverage = min(1.0, min(radiusUv.x * ScreenSize.x, radiusUv.y * ScreenSize.y));
    }
    float centerItem = isItem(texCoord);
    bool destinationValid;
    float destinationDepth = destinationDepthAt(texCoord, destinationValid);
    float glowMask = 0.0;
    float totalWeight = 0.0;
    if (destinationValid) {
        for (int i = 0; i < 8; i++) {
            float angle = float(i) * 0.785398;
            vec2 offset = vec2(cos(angle), sin(angle)) * radiusUv;
            float w = (i < 4) ? 0.16 : 0.09;
            glowMask += outlineSample(texCoord + offset, destinationDepth) * w;
            totalWeight += w;
        }
    }
    glowMask = totalWeight > 0.0
            ? (glowMask / totalWeight) * (1.0 - centerItem)
            : 0.0;

    float t = 0.5 + 0.5 * sin(dot(texCoord, vec2(6.2831, 3.1415)) - FrameTimeCounter * WaveSpeed);
    vec3 outlineColor = mix(InnerColor, OuterColor, t);
    fragColor = finalizeGlow(glowMask, Intensity, outlineColor);
    fragColor.rgb *= subpixelCoverage;
}
