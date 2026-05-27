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
float in `[0.1, 1.0]`; out-of-range or non-numeric values fall back to `1.0`.

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
      "0": 1.0,
      "1": 0.6667,
      "2": 0.5882,
      "3": 0.5,
      "4": 0.3333
    }
  }
}
```

(Values come from the pack's `shaders.properties` `size.buffer.colortex10` directives.)

## Reload behavior

Gallium reads the hint and resolves the option once per Iris pack reload — per-frame
overhead in the steady state is one identity check and a cached float read. Changes to
the pack option take effect when Iris reloads (default keybind `R`).
