#version 450
#moj_import <gallium:glow_common.glsl>

uniform sampler2D DiffuseSampler;
uniform sampler2D MaskSampler;
uniform sampler2D MaskDepthSampler;
uniform sampler2D SceneDepthSampler;

#ifdef GALLIUM_HAS_MASK_INSTANCES
struct GalliumGlowValues {
#else
layout(std140) uniform GlowUniforms {
#endif
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
    vec4 GalliumMaskBounds;
#ifdef GALLIUM_HAS_MASK_STORAGE
    vec4 GalliumMaskRegion;
    vec4 GalliumMaskStorage;
#endif
};

#ifdef GALLIUM_HAS_MASK_INSTANCES
flat in int gallium_InternalInstance;
layout(std140) uniform GlowUniforms { GalliumGlowValues gallium_InternalValues[16]; };
#define FrameTimeCounter gallium_InternalValues[gallium_InternalInstance].FrameTimeCounter
#define ScreenSize gallium_InternalValues[gallium_InternalInstance].ScreenSize
#define Intensity gallium_InternalValues[gallium_InternalInstance].Intensity
#define PulseSpeed gallium_InternalValues[gallium_InternalInstance].PulseSpeed
#define WaveSpeed gallium_InternalValues[gallium_InternalInstance].WaveSpeed
#define InnerColor gallium_InternalValues[gallium_InternalInstance].InnerColor
#define OuterColor gallium_InternalValues[gallium_InternalInstance].OuterColor
#define ShaderAlign gallium_InternalValues[gallium_InternalInstance].ShaderAlign
#define ShaderOffset gallium_InternalValues[gallium_InternalInstance].ShaderOffset
#define GalliumItemDistance gallium_InternalValues[gallium_InternalInstance].GalliumItemDistance
#define GalliumWorldToUv gallium_InternalValues[gallium_InternalInstance].GalliumWorldToUv
#define GalliumMaskBounds gallium_InternalValues[gallium_InternalInstance].GalliumMaskBounds
#define GalliumMaskRegion gallium_InternalValues[gallium_InternalInstance].GalliumMaskRegion
#define GalliumMaskStorage gallium_InternalValues[gallium_InternalInstance].GalliumMaskStorage
#endif

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

// Atlas storage preserves the logical full-screen mask, including untouched world depth.
#ifdef GALLIUM_HAS_MASK_STORAGE
uniform sampler2D GalliumMaskBaseDepthSampler;
#endif
#ifdef GALLIUM_HAS_MASK_INSTANCES
layout(rg32ui, binding=0) readonly uniform uimage2D GalliumVisibilityImage;
#endif
bool visibilityMask() {
#ifdef GALLIUM_HAS_MASK_INSTANCES
    return GalliumMaskStorage.z == 2.0;
#else
    return false;
#endif
}
float visibilityAlpha(ivec2 coord) {
#ifdef GALLIUM_HAS_MASK_INSTANCES
    coord=clamp(coord,ivec2(0),ivec2(ScreenSize)-1);
    uvec2 bits=imageLoad(GalliumVisibilityImage,coord).xy;
    int id=int(GalliumMaskStorage.x);
    return (bits[id / 32] & (1u << uint(id & 31))) != 0u ? 1.0 : 0.0;
#else
    return 0.0;
#endif
}
bool storedMask() {
#ifdef GALLIUM_HAS_MASK_STORAGE
    return GalliumMaskStorage.z == 1.0 || GalliumMaskStorage.z == 2.0;
#else
    return false;
#endif
}
bool storedHand() {
#ifdef GALLIUM_HAS_MASK_STORAGE
    return storedMask() && GalliumMaskStorage.w == 1.0;
#else
    return false;
#endif
}
ivec2 maskSize() { return storedMask() ? ivec2(ScreenSize) : textureSize(MaskSampler, 0); }
ivec2 maskDepthSize() { return storedMask() ? ivec2(ScreenSize) : textureSize(MaskDepthSampler, 0); }
ivec2 sceneSize() { return storedHand() ? maskDepthSize() : textureSize(SceneDepthSampler, 0); }
bool maskStoredAt(ivec2 coord) {
#ifdef GALLIUM_HAS_MASK_STORAGE
    return all(greaterThanEqual(coord, ivec2(GalliumMaskRegion.xy)))
            && all(lessThan(coord, ivec2(GalliumMaskRegion.xy + GalliumMaskRegion.zw)));
#else
    return false;
#endif
}
ivec2 storedCoord(ivec2 coord) {
#ifdef GALLIUM_HAS_MASK_STORAGE
    return coord + ivec2(GalliumMaskStorage.xy);
#else
    return coord;
#endif
}
vec4 maskColorFetch(ivec2 coord) {
    if(visibilityMask()) return vec4(0.0,0.0,0.0,visibilityAlpha(coord));
    if (!storedMask()) return texelFetch(MaskSampler, coord, 0);
    coord = clamp(coord, ivec2(0), maskSize() - 1);
    return maskStoredAt(coord) ? texelFetch(MaskSampler, storedCoord(coord), 0) : vec4(0.0);
}
float maskDepthFetch(ivec2 coord) {
#ifdef GALLIUM_HAS_MASK_INSTANCES
    if(visibilityMask()) return texelFetch(GalliumMaskBaseDepthSampler,clamp(coord,ivec2(0),ivec2(ScreenSize)-1),0).r;
#endif
    if (!storedMask()) return texelFetch(MaskDepthSampler, coord, 0).r;
    coord = clamp(coord, ivec2(0), maskDepthSize() - 1);
    if (maskStoredAt(coord)) return texelFetch(MaskDepthSampler, storedCoord(coord), 0).r;
#ifdef GALLIUM_HAS_MASK_STORAGE
    if (!storedHand()) return texelFetch(GalliumMaskBaseDepthSampler, coord, 0).r;
#endif
    return 1.0;
}
float sceneDepthFetch(ivec2 coord) {
    return storedHand() ? maskDepthFetch(coord) : texelFetch(SceneDepthSampler, coord, 0).r;
}
vec4 maskColorGather(ivec2 base, vec2 originalUv) {
    if(visibilityMask()) return vec4(visibilityAlpha(base+ivec2(0,1)),visibilityAlpha(base+ivec2(1)),visibilityAlpha(base+ivec2(1,0)),visibilityAlpha(base));
    if (!storedMask()) return textureGather(MaskSampler, originalUv, 3);
    if (maskStoredAt(base) && maskStoredAt(base + ivec2(1))) {
        vec2 uv = (vec2(storedCoord(base)) + vec2(1.0)) / vec2(textureSize(MaskSampler, 0));
        return textureGather(MaskSampler, uv, 3);
    }
    return vec4(maskColorFetch(base + ivec2(0, 1)).a, maskColorFetch(base + ivec2(1)).a,
            maskColorFetch(base + ivec2(1, 0)).a, maskColorFetch(base).a);
}
vec4 maskDepthGather(ivec2 base, vec2 originalUv) {
#ifdef GALLIUM_HAS_MASK_INSTANCES
    if(visibilityMask()) return textureGather(GalliumMaskBaseDepthSampler,originalUv,0);
#endif
    if (!storedMask()) return textureGather(MaskDepthSampler, originalUv, 0);
    if (maskStoredAt(base) && maskStoredAt(base + ivec2(1))) {
        vec2 uv = (vec2(storedCoord(base)) + vec2(1.0)) / vec2(textureSize(MaskDepthSampler, 0));
        return textureGather(MaskDepthSampler, uv, 0);
    }
    return vec4(maskDepthFetch(base + ivec2(0, 1)), maskDepthFetch(base + ivec2(1)),
            maskDepthFetch(base + ivec2(1, 0)), maskDepthFetch(base));
}
vec4 sceneDepthGather(ivec2 base, vec2 originalUv) {
    return storedHand() ? maskDepthGather(base, originalUv) : textureGather(SceneDepthSampler, originalUv, 0);
}

ivec2 scaledTextureSize(ivec2 physicalSize, vec2 scale) {
    return max(ivec2(round(vec2(physicalSize) * scale)), ivec2(1));
}

ivec2 activeRegionOrigin(ivec2 physicalSize, ivec2 activeSize, vec2 uvOffset) {
    // ShaderOffset contains an integer-pixel viewport origin plus a sub-pixel temporal offset.
    // Recover the stable region origin by rounding to the nearest pixel; keep the fractional
    // remainder in the sample coordinate below so jitter still moves this frame's lookup.
    ivec2 maxOrigin = max(physicalSize - activeSize, ivec2(0));
    return clamp(ivec2(round(uvOffset * vec2(physicalSize))), ivec2(0), maxOrigin);
}

ivec2 activeTexel(vec2 uv, ivec2 physicalSize, vec2 scale, vec2 uvOffset, out bool valid) {
    ivec2 activeSize = min(scaledTextureSize(physicalSize, scale), physicalSize);
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
    ivec2 physicalSize = maskSize();
    ivec2 activeSize = min(scaledTextureSize(maskSize(),
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
    float alpha = maskColorFetch(coord).a;
    if (alpha <= 0.01) return 0.0;
    if (compareDepth) {
        float sourceDepth = maskDepthFetch(coord);
        vec2 sourceUv = (vec2(coord) + vec2(0.5)
                - maskUvOffset() * vec2(maskSize())) / vec2(activeSize);
        bool sourceSceneValid;
        float sourceSceneDepth = sceneDepthAt(
                sourceUv, ShaderAlign.w >= 0.0, sourceSceneValid);
        if (!sourceSceneValid || sourceDepth > min(destinationDepth, sourceSceneDepth)) return 0.0;
    }
    return 1.0;
}


const int QaVisibilityFastEnabled=1;
bool directVisibilityGrid() {
#ifdef GALLIUM_HAS_MASK_STORAGE
    return QaVisibilityFastEnabled!=0 && visibilityMask() && GalliumMaskStorage.w==0.0
            && all(equal(ShaderAlign,vec4(1.0))) && all(equal(ShaderOffset,vec4(0.0)))
            && all(equal(ivec2(ScreenSize),textureSize(SceneDepthSampler,0)));
#else
    return false;
#endif
}
float reconstructedVisibility(vec2 uv,float destinationDepth,bool compareDepth) {
    ivec2 size=ivec2(ScreenSize);
    vec2 center=uv*vec2(size)-vec2(0.5);
    if(any(lessThan(uv,vec2(0.0))) || any(greaterThanEqual(uv,vec2(1.0)))
            || any(lessThanEqual(center,vec2(-1.0))) || any(greaterThanEqual(center,vec2(size))))return 0.0;
    ivec2 base=ivec2(floor(center));
    vec2 fraction=fract(center),inverseFraction=vec2(1.0)-fraction;
    vec4 visible=vec4(visibilityAlpha(base+ivec2(0,1)),visibilityAlpha(base+ivec2(1)),
            visibilityAlpha(base+ivec2(1,0)),visibilityAlpha(base));
    ivec4 x=base.x+ivec4(0,1,1,0),y=base.y+ivec4(1,1,0,0);
    bvec4 inside=bvec4(x.x>=0 && x.x<size.x && y.x>=0 && y.x<size.y,
            x.y>=0 && x.y<size.x && y.y>=0 && y.y<size.y,
            x.z>=0 && x.z<size.x && y.z>=0 && y.z<size.y,
            x.w>=0 && x.w<size.x && y.w>=0 && y.w<size.y);
    visible=mix(vec4(0.0),visible,inside);
    if(!any(greaterThan(visible,vec4(0.0))))return 0.0;
#ifdef GALLIUM_HAS_MASK_STORAGE
    if(compareDepth) {
        vec2 gatherUv=(vec2(base)+vec2(1.0))/vec2(size);
        vec4 sourceDepth=textureGather(GalliumMaskBaseDepthSampler,gatherUv,0);
        vec4 sceneDepth=textureGather(SceneDepthSampler,gatherUv,0);
        visible=mix(visible,vec4(0.0),greaterThan(sourceDepth,min(vec4(destinationDepth),sceneDepth)));
    }
#endif
    float result=0.0;
    result+=visible.w*inverseFraction.x*inverseFraction.y;
    result+=visible.z*fraction.x*inverseFraction.y;
    result+=visible.x*inverseFraction.x*fraction.y;
    result+=visible.y*fraction.x*fraction.y;
    return result;
}

float reconstructedMask(vec2 uv, float destinationDepth, bool compareDepth) {
    if(directVisibilityGrid())return reconstructedVisibility(uv,destinationDepth,compareDepth);
    MaskGrid grid = maskGrid(uv);
    if (!grid.valid) return 0.0;

    vec2 inverseFraction = vec2(1.0) - grid.fraction;
    // An unshifted one-to-one grid can gather the exact same four integer texels.
    // Keep the general path for SR viewports, temporal offsets and pooled depth.
    if (all(equal(ShaderAlign, vec4(1.0))) && all(equal(ShaderOffset, vec4(0.0)))
            && all(equal(maskSize(), maskDepthSize()))
            && all(equal(maskSize(), sceneSize()))) {
        ivec2 size = maskSize();
        vec2 gatherUv = (vec2(grid.baseCoord) + vec2(1.0)) / vec2(size);
        // textureGather order: upper-left, upper-right, lower-right, lower-left.
        vec4 alpha = maskColorGather(grid.baseCoord, gatherUv);
        ivec4 x = grid.baseCoord.x + ivec4(0, 1, 1, 0);
        ivec4 y = grid.baseCoord.y + ivec4(1, 1, 0, 0);
        bvec4 inRegion = bvec4(
                x.x >= 0 && x.x < size.x && y.x >= 0 && y.x < size.y,
                x.y >= 0 && x.y < size.x && y.y >= 0 && y.y < size.y,
                x.z >= 0 && x.z < size.x && y.z >= 0 && y.z < size.y,
                x.w >= 0 && x.w < size.x && y.w >= 0 && y.w < size.y);
        vec4 visible = mix(vec4(1.0), vec4(0.0), lessThanEqual(alpha, vec4(0.01)));
        visible = mix(vec4(0.0), visible, inRegion);
        if (!any(greaterThan(visible, vec4(0.0)))) return 0.0;
        if (compareDepth) {
            vec4 sourceDepth = maskDepthGather(grid.baseCoord, gatherUv);
            vec4 sceneDepth = sceneDepthGather(grid.baseCoord, gatherUv);
            visible = mix(visible, vec4(0.0), greaterThan(sourceDepth, min(vec4(destinationDepth), sceneDepth)));
        }
        // Preserve the original accumulation order and each pair of multiplications.
        float gathered = 0.0;
        gathered += visible.w * inverseFraction.x * inverseFraction.y;
        gathered += visible.z * grid.fraction.x * inverseFraction.y;
        gathered += visible.x * inverseFraction.x * grid.fraction.y;
        gathered += visible.y * grid.fraction.x * grid.fraction.y;
        return gathered;
    }
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
    ivec2 coord = activeTexel(uv, sceneSize(),
            vec2(ShaderAlign.y, abs(ShaderAlign.w)), sceneUvOffset(), valid);
    if (!valid) return 1.0;
    float sceneDepth = sceneDepthFetch(coord);
    if (!exactReplay) {
        ivec2 physicalSize = sceneSize();
        ivec2 size = min(scaledTextureSize(sceneSize(),
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
                        sceneDepthFetch(neighbour));
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
#ifdef GALLIUM_HAS_MASK_STORAGE
    if(directVisibilityGrid()) {
        ivec2 size=ivec2(ScreenSize);
        vec2 texel=uv*vec2(size);
        valid=all(greaterThanEqual(uv,vec2(0.0))) && all(lessThan(uv,vec2(1.0)))
                && all(greaterThanEqual(texel,vec2(0.0))) && all(lessThan(texel,vec2(size)));
        if(!valid)return 1.0;
        ivec2 coord=clamp(ivec2(floor(texel)),ivec2(0),size-1);
        return min(texelFetch(GalliumMaskBaseDepthSampler,coord,0).r,texelFetch(SceneDepthSampler,coord,0).r);
    }
#endif

    ivec2 destinationCoord = activeTexel(uv, maskDepthSize(),
            vec2(ShaderAlign.x, ShaderAlign.z), maskUvOffset(), valid);
    if (!valid) return 1.0;
    float destinationMaskDepth = maskDepthFetch(destinationCoord);
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
    float t = 0.5 + 0.5 * sin(dot(texCoord, vec2(6.2831, 3.1415)) - FrameTimeCounter * WaveSpeed);
    vec3 outlineColor = mix(InnerColor, OuterColor, t);
#ifdef GALLIUM_HAS_MASK_BOUNDS
    // The engine provides a conservative physical-pixel rectangle of this exact replay.
    // Zero means unavailable. The extra two texels cover floor/ceil reconstruction taps.
    // Keep finalizeGlow's empty-mask output, including alpha and any pack-specific behavior.
    if (all(greaterThan(GalliumMaskBounds.zw, GalliumMaskBounds.xy))) {
        vec2 physicalSize = vec2(maskSize());
        vec2 activeSize = vec2(min(scaledTextureSize(maskSize(),
                vec2(ShaderAlign.x, ShaderAlign.z)), ivec2(physicalSize)));
        vec2 center = texCoord * activeSize + maskUvOffset() * physicalSize;
        vec2 reach = abs(radiusUv) * activeSize + vec2(2.0);
        if (any(lessThan(center + reach, GalliumMaskBounds.xy))
                || any(greaterThan(center - reach, GalliumMaskBounds.zw))) {
            fragColor = finalizeGlow(0.0, Intensity, outlineColor);
            fragColor.rgb *= subpixelCoverage;
            return;
        }
    }
#endif
    float centerItem = isItem(texCoord);
    if (centerItem == 1.0) {
        fragColor = finalizeGlow(0.0, Intensity, outlineColor);
        fragColor.rgb *= subpixelCoverage;
        return;
    }
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

    fragColor = finalizeGlow(glowMask, Intensity, outlineColor);
    fragColor.rgb *= subpixelCoverage;
}
