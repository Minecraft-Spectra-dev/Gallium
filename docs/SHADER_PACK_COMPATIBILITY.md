# Shader Pack Projection Compatibility

Gallium needs to know the **active render region, internal-resolution scale, and temporal
jitter** used by a shader pack so the glow mask lands on the same pixels as the scene.

## Super Resolution definitions (automatic)

Shader packs that implement IReallyWantToSleep/Super Resolution do **not** need to duplicate
their standard projection metadata in `gallium.json`. Every Gallium build searches the shader
source root (`shaders/`, next to `shaders.properties`) in this order:

1. `superresolution.v3.json`
2. `superresolution.v2.json`
3. `superresolution.v1.json`
4. `superresolution.json` (schema-based or legacy)

Gallium parses legacy and schema v1-v3 into one version-neutral model. The definition supplies
the active dimension profile, input region, and whether jitter is owned by SR or by the pack.
Live values that are deliberately absent from JSON are resolved at runtime:

- `-1` region extents use SR's integer render width/height;
- `-2` uses the display width/height;
- mod-owned jitter uses SR's active schema processor and current pixel offset;
- pack-owned jitter resolves the declared `const`, Iris `uniform`, or Iris `variable`.

When external SR is inactive, Gallium reuses the same selected SR profile to locate its semantic
render-size inputs (motion vectors first, then color/depth). It asks Iris's already-preprocessed
`PackDirectives` for that input target's exact integer extent and cross-checks a coherent live
shader-pack viewport declaration when one exists. This covers a pack's built-in FSR path without
duplicating its private option-to-scale table in `gallium.json`.

For a proven built-in FSR viewport, Gallium also follows every effective Iris `ProgramId` fallback
used by world items, blocks, armor glint and hands. Exact jitter additionally includes the
Terrain/Water/Distant-Horizons programs that produce sampled scene depth. It is accepted only when
all of those preprocessed vertex sources contain the restricted affine `FsrScaleVS` transform and
every active call passes the same simple uniform (or all pass literal zero). A uniform name by
itself is never evidence. Mixed branches, a custom expression, a missing fallback or a different
transform keeps the exact scale but selects conservative depth handling. Weather joins this
consensus only when Iris reports `rain.depth=true`, because otherwise it does not write main depth.

X and Y scale are computed separately, so odd display sizes retain SR's exact integer rounding.
If a macro definition cannot be fully preprocessed, a runtime value is unavailable, or a value
is non-finite, Gallium keeps any safely resolved scale but drops exact jitter and uses its bounded
depth fallback. It never guesses an unresolved preprocessor branch. With SR installed Gallium uses
SR's official JSON preprocessor; without an SR JAR it uses Iris's active options and captured
environment defines. `SR_INSTALLED` is then definitively absent, so portable
`#ifdef SR_INSTALLED` branches resolve normally; an unavailable dynamic `SR_*` macro only makes
the definition fail closed when it is referenced by the branch Iris actually selects.

The macro environment always includes Iris environment defines and the pack's effective boolean
and string options. When SR is installed, Gallium adds those option macros to SR's official JSON
preprocessor before SR's dynamic built-ins, so installing SR does not select a different pack
option branch. Missing option/runtime values fail closed.

For schema v1-v3, an omitted input or output `region` follows SR's protocol default
`[0, 0, -1, -1]` (full render resolution). Explicit positions must be non-negative; width and
height must be positive pixels, `-1` (render size), or `-2` (screen size).

When a standard SR definition resolves successfully, it takes priority over an older
`gallium.json` for both external SR and a compatible built-in upscale path.

Only a shader pack with a deliberately non-standard SR projection should override the definition:

```jsonc
{
  "override_sr_definition": true,
  "internal_resolution_scale": { /* complete non-standard declaration */ },
  "temporal_jitter": { /* complete declaration when exact replay is required */ }
}
```

The scale declaration is required for an explicit SR override; `temporal_jitter` may be omitted,
but then Gallium deliberately uses its conservative depth fallback. Legacy non-override hints do
not alter an automatically proven SR-compatible viewport and are never needed to obtain it.

## Explicit `gallium.json` override

For other internally-scaled packs, pack authors (or end users) place a small JSON file in the
pack root:

```
shaderpacks/
  YourPack/
    gallium.json   ← here
    shaders/
    ...
```

If neither an SR definition/runtime viewport nor this file is available, Gallium assumes `1.0`.
The outline still renders correctly on packs without internal scaling, but packs with an unknown
private projection transform may require an explicit declaration.

## Format

```jsonc
{
  "internal_resolution_scale": {
    "option": "ResolutionScale"
  }
}
```

`option` names a shader pack option Gallium can read via Iris. The value is parsed as a
float and clamped into `[0.1, 1.0]`; non-numeric values fall back to `1.0`.

### Enum / step options

For a non-SR pack that exposes the scale as an integer step, add a `values` map:

```jsonc
{
  "internal_resolution_scale": {
    "option": "FSR2_SCALE",
    "values": {
      "0": 0.75,
      "1": 0.6667,
      "2": 0.5882,
      "3": 0.5,
      "4": 0.4
    }
  }
}
```

Steps not listed fall back to `1.0`.

## Example: Kappa / Nostalgia

Both packs expose a single `ResolutionScale` float option (default `0.75`):

```json
{
  "internal_resolution_scale": {
    "option": "ResolutionScale"
  }
}
```

## Example: iterationRP

No `gallium.json` is required. Its SR profile declares `motion_vectors.src=colortex10`; Iris's
resolved size directive for that semantic input supplies the exact built-in FSR viewport after
option preprocessing and pixel rounding. External SR continues to use the definition's live `-1`
extent. Unsupported projection exceptions remain conservative rather than requiring player edits.

## Temporal jitter

Gallium never guesses TAA from custom-uniform names such as `taaJitter`, `taaOffset`, or
`TAAJitter`. Exact replay comes from one of three contracts:

1. An SR schema profile with shaderpack-owned `source_config`, whose declared
   `const`/`uniform`/`variable` is read directly.
2. Mod-owned SR jitter adapted by the active SR schema processor; because the offset is added after
   the scaled viewport transform, it becomes `2 * offset / screenExtent` NDC per axis (equivalently
   `scale * 2 * offset / renderExtent`).
3. The built-in FSR source proof described above, where the actual preprocessed call sites identify
   one shader-facing NDC uniform or unanimous literal zero.

The third path uses reflection only after the formula and every relevant call site have been proven.
A variable merely existing while TAA is off, conditional zero-jitter hand code, different uniforms,
or a scale-before-jitter formula therefore cannot accidentally enable exact replay.

For a non-SR pack, an explicit `gallium.json` `temporal_jitter` declaration remains available:
Gallium reads Iris's current `frameCounter`, evaluates the declared R2 sample, and applies the exact
per-axis scale and NDC offset to every world-item replay.
`pixel_rounding: "floor"` mirrors packs that compute their internal width and height with
`floor(viewDimension * scale)`; the effective X and Y scales are therefore resolved independently.

If the declaration is missing or invalid, its option cannot be resolved, Iris's frame counter is
unavailable, or Gallium could not recover the captured projection matrix, exact replay stays off.
Minecraft 1.21.6+ then retains the bounded 3x3 farthest-neighbour depth-pool fallback; older
versions retain their depth-adaptive bias fallback. Gallium intentionally does not guess any of
these fields from uniform names.

## Outline occlusion sampling

For an expanded world outline, every source mask texel must pass both its own scene-depth test and
the nearest depth at the destination pixel where the outline will be drawn. Unknown packs keep
captured world geometry in mask depth as a GPU-side fallback. For an exact Iris replay, Gallium
clears mask depth to the far plane so small pack-vs-vanilla Z or alpha-coverage differences cannot
discard a mask fragment; the composite shader then owns world/item occlusion after the complete mask
has been captured. Without Iris, scene depth supplies the vanilla hand rendered after the hand-stage
clear; with Iris, Gallium selects the pre-clear snapshot after Iris's custom solid and translucent
hand phases, so that snapshot already contains world + hand:

```glsl
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
    // ShaderOffset is integer-pixel region origin plus sub-pixel temporal jitter. Rounding
    // recovers the stable region boundary while the unrounded value below keeps the jitter.
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
    vec2 p = uv * vec2(activeSize)
        + maskUvOffset() * vec2(physicalSize) - vec2(0.5);
    bool valid = all(greaterThanEqual(uv, vec2(0.0)))
        && all(lessThan(uv, vec2(1.0)))
        && all(greaterThan(p, vec2(regionOrigin) - vec2(1.0)))
        && all(lessThan(p, vec2(regionEnd)))
        && all(greaterThan(p, vec2(-1.0)))
        && all(lessThan(p, vec2(physicalSize)));
    return MaskGrid(activeSize, regionOrigin, regionEnd,
        ivec2(floor(p)), fract(p), valid);
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
        ivec2 regionOrigin = activeRegionOrigin(physicalSize, size, sceneUvOffset());
        ivec2 regionEnd = min(regionOrigin + size, physicalSize);
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                ivec2 p = clamp(coord + ivec2(x, y), regionOrigin, regionEnd - 1);
                sceneDepth = max(sceneDepth, texelFetch(SceneDepthSampler, p, 0).r);
            }
        }
    }
    return sceneDepth;
}

float visibleMaskTap(ivec2 coord, ivec2 activeSize,
        ivec2 regionOrigin, ivec2 regionEnd,
        float destinationDepth, bool compareDepth) {
    if (any(lessThan(coord, regionOrigin)) || any(greaterThanEqual(coord, regionEnd))) return 0.0;
    if (texelFetch(MaskSampler, coord, 0).a <= 0.01) return 0.0;
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
    vec2 f = grid.fraction, inv = vec2(1.0) - f;
    return visibleMaskTap(grid.baseCoord, grid.activeSize,
            grid.regionOrigin, grid.regionEnd, destinationDepth, compareDepth) * inv.x * inv.y
        + visibleMaskTap(grid.baseCoord + ivec2(1, 0), grid.activeSize,
            grid.regionOrigin, grid.regionEnd, destinationDepth, compareDepth) * f.x * inv.y
        + visibleMaskTap(grid.baseCoord + ivec2(0, 1), grid.activeSize,
            grid.regionOrigin, grid.regionEnd, destinationDepth, compareDepth) * inv.x * f.y
        + visibleMaskTap(grid.baseCoord + ivec2(1, 1), grid.activeSize,
            grid.regionOrigin, grid.regionEnd, destinationDepth, compareDepth) * f.x * f.y;
}

float isItem(vec2 uv) {
    return reconstructedMask(uv, 1.0, false);
}

float outlineSample(vec2 sourceUv, vec2 destinationUv) {
    bool valid;
    ivec2 destinationCoord = activeTexel(destinationUv, MaskDepthSampler,
        vec2(ShaderAlign.x, ShaderAlign.z), maskUvOffset(), valid);
    if (!valid) return 0.0;
    bool sceneValid;
    float destinationSceneDepth = sceneDepthAt(
        destinationUv, ShaderAlign.w >= 0.0, sceneValid);
    if (!sceneValid) return 0.0;
    float destinationDepth = min(
        texelFetch(MaskDepthSampler, destinationCoord, 0).r, destinationSceneDepth);
    return reconstructedMask(sourceUv, destinationDepth, true);
}
```

The coordinate passed to `texelFetch` is an absolute physical-texture coordinate. Consequently,
an active region beginning at `(originX, originY)` must be bounded by
`[regionOrigin, min(regionOrigin + activeSize, physicalSize))`; comparing that absolute coordinate
against `[0, activeSize)` is only valid for an origin of zero. `activeRegionOrigin` reconstructs
the integer-pixel region origin by rounding the combined UV offset, while the unrounded
`uvOffset * physicalSize` remains in every sample calculation. The fractional residual therefore
continues to apply the current temporal jitter without moving the legal region bounds. The same
region bounds must be used for mask interpolation taps and for the conservative 3×3 scene-depth
neighbourhood, so neither path leaks into an adjacent packed region.

Use `texelFetch` (and `NEAREST` sampler state) for the mask color and both depth textures. To smooth
an internally scaled mask, manually interpolate the four per-texel occupancy/visibility results as
shown above; never interpolate depth itself. Filtered `texture()` depth reads or independently
clamped UVs can select color from one side of a silhouette and depth from the other, producing a
thin embedded/penetrating outline. Treat a source UV outside `[0,1)` as invalid instead of clamping
it to the border, which would duplicate the edge silhouette. The mask's alpha is the occupancy
signal; RGB may be black for a valid item texture.

Do not compare both samplers only at `destinationUv`: that loses the outlined pixel's depth after
the mask shape is expanded. Do not compare only source mask depth with the destination either: an
exact Iris replay deliberately contains source pixels hidden by the original world draw, and those
pixels can otherwise expand out of an occluder edge. Do not use only `SceneDepthSampler` at the
destination: without Iris, the live attachment may contain only the first-person hand after vanilla
clears world depth, so it misses blocks, entities, and other world items retained by
`MaskDepthSampler`. The minimum is valid because Gallium exposes both samplers as forward-Z
(`0` near, `1` far). For exact Iris replays, use mask alpha as the center-item occupancy test;
comparing replay depth with the pack's original depth at the same pixel can create a one-pixel rim
when the pack writes a slightly different clip-space Z. Avoid a fixed raw-depth epsilon in the
expanded-sample comparison; its world-space tolerance grows with distance and can let part of an
outline enter foreground geometry.

This does not require changes to a resource pack's vertex shader. Gallium captures and selects the
appropriate depth attachments in Java; the fragment shader only needs the source-to-destination
comparison above because it owns the outline expansion offsets.

`ShaderAlign` is `(maskScaleX, sceneScaleX, maskScaleY, signedSceneScaleY)`. A positive W component
means exact projection alignment; a negative W means the fragment shader should retain its bounded
neighbourhood fallback. Always use `abs(ShaderAlign.w)` for scene Y coordinates. `ShaderOffset` is
`(maskOffsetX, sceneOffsetX, maskOffsetY, sceneOffsetY)` in full-texture UV units. Iris's declared
NDC jitter is divided by two before it is written here, so add the corresponding offset before
converting a final output UV to an internal texel. Keep the mask and scene offsets separate when a
mask projection replay falls back.

## Reload behavior

Gallium reads the hint and resolves options once per Iris pack reload. Per-frame projection results
are cached by Iris frame counter and viewport size. Changes to the pack option take effect when Iris
reloads (default keybind `R`).
