# Shader Pack Compatibility (`gallium.json`)

Gallium needs to know the **internal-resolution scale** the active shader pack uses,
to align the glow outline mask with what the player sees on the main render target.
Pack authors (or end users) place a single small JSON file in the pack root to wire this up:

```
shaderpacks/
  YourPack/
    gallium.json   ← here
    shaders/
    ...
```

If the file is missing Gallium assumes `1.0` (full-resolution rendering); the outline still
renders correctly on packs without internal scaling, but world occlusion may "see through"
geometry on packs that do scale (Kappa, Nostalgia, iterationRP, ...).

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

For packs that expose the scale as an integer step (e.g. iterationRP's `FSR2_SCALE`
takes values `0`–`4` mapped to a fixed table inside the pack), add a `values` map:

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

```json
{
  "internal_resolution_scale": {
    "option": "FSR2_SCALE",
    "values": {
      "-1": 1.0,
      "0": 1.0,
      "1": 0.6667,
      "2": 0.5882,
      "3": 0.5,
      "4": 0.3333
    },
    "pixel_rounding": "floor"
  },
  "temporal_jitter": {
    "sequence": "r2",
    "units": "ndc_per_view_size",
    "transform_order": "after_internal_scale",
    "phase": 0.5,
    "x_multiplier": 0.754877666,
    "y_multiplier": 0.569840291,
    "index_offset": 1,
    "requires": {
      "RENDERING_MODE": "false",
      "CUSTOM_RENDER_RESOLUTION": "false",
      "DISABLE_PLAYER_TAA_MOTION_BLUR": "false"
    },
    "period": {
      "option": "FSR2_SCALE",
      "values": { "0": 16, "1": 18, "2": 23, "3": 32, "4": 72 }
    }
  }
}
```

(Values and the R2 sequence come directly from the pack's `shaders.properties` declarations.)
`RENDERING_MODE=true` uses iterationRP's separate 617-frame sequence, while custom render resolution
and player TAA exclusion change the projection applied by one or more entity passes. The declaration
therefore explicitly falls back for each of those modes. It intentionally omits native
`FSR2_SCALE=-1` from the period table: that also avoids mistaking the usual external-super-resolution
configuration for iterationRP's built-in FSR2 transform. An external mode used with another FSR2
value needs its own explicit integration before it can use exact replay.

## Temporal jitter

Gallium does not discover or replay shader-pack TAA offsets by reflecting custom uniforms such as
`taaJitter`, `taaOffset`, or `TAAJitter`. A declared variable can remain present while TAA is off,
different passes can conditionally skip it, and packs apply the offset on different sides of their
internal-resolution transform. Uniform presence alone is therefore not an exact projection contract.

When `temporal_jitter` is declared, Gallium reads Iris's current `frameCounter`, evaluates the
declared R2 sample, and applies the exact per-axis scale and NDC offset to every world-item replay.
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
ivec2 activeTexel(vec2 uv, sampler2D tex, vec2 scale, vec2 uvOffset, out bool valid) {
    ivec2 physicalSize = textureSize(tex, 0);
    ivec2 activeSize = max(ivec2(round(vec2(physicalSize) * scale)), ivec2(1));
    vec2 texel = uv * vec2(activeSize) + uvOffset * vec2(physicalSize);
    valid = all(greaterThanEqual(uv, vec2(0.0))) &&
            all(lessThan(uv, vec2(1.0))) &&
            all(greaterThanEqual(texel, vec2(0.0))) &&
            all(lessThan(texel, vec2(activeSize)));
    if (!valid) return ivec2(0);
    return clamp(ivec2(floor(texel)), ivec2(0), activeSize - 1);
}

vec2 maskUvOffset() {
    return vec2(ShaderOffset.x, ShaderOffset.z);
}

vec2 sceneUvOffset() {
    return vec2(ShaderOffset.y, ShaderOffset.w);
}

float sceneDepthAt(vec2 uv, bool exactReplay, out bool valid) {
    ivec2 coord = activeTexel(uv, SceneDepthSampler,
        vec2(ShaderAlign.y, abs(ShaderAlign.w)), sceneUvOffset(), valid);
    if (!valid) return 1.0;
    float sceneDepth = texelFetch(SceneDepthSampler, coord, 0).r;
    if (!exactReplay) {
        ivec2 size = max(ivec2(round(vec2(textureSize(SceneDepthSampler, 0))
            * vec2(ShaderAlign.y, abs(ShaderAlign.w)))), ivec2(1));
        for (int y = -1; y <= 1; y++) {
            for (int x = -1; x <= 1; x++) {
                ivec2 p = clamp(coord + ivec2(x, y), ivec2(0), size - 1);
                sceneDepth = max(sceneDepth, texelFetch(SceneDepthSampler, p, 0).r);
            }
        }
    }
    return sceneDepth;
}

float visibleMaskTap(ivec2 coord, ivec2 activeSize,
        float destinationDepth, bool compareDepth) {
    if (any(lessThan(coord, ivec2(0))) || any(greaterThanEqual(coord, activeSize))) return 0.0;
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
    ivec2 physicalSize = textureSize(MaskSampler, 0);
    ivec2 activeSize = max(ivec2(round(vec2(physicalSize)
        * vec2(ShaderAlign.x, ShaderAlign.z))), ivec2(1));
    vec2 p = uv * vec2(activeSize) + maskUvOffset() * vec2(physicalSize) - vec2(0.5);
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0)))
            || any(lessThanEqual(p, vec2(-1.0))) || any(greaterThanEqual(p, vec2(activeSize)))) {
        return 0.0;
    }
    ivec2 base = ivec2(floor(p));
    vec2 f = fract(p), inv = vec2(1.0) - f;
    return visibleMaskTap(base, activeSize, destinationDepth, compareDepth) * inv.x * inv.y
        + visibleMaskTap(base + ivec2(1, 0), activeSize, destinationDepth, compareDepth) * f.x * inv.y
        + visibleMaskTap(base + ivec2(0, 1), activeSize, destinationDepth, compareDepth) * inv.x * f.y
        + visibleMaskTap(base + ivec2(1, 1), activeSize, destinationDepth, compareDepth) * f.x * f.y;
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
