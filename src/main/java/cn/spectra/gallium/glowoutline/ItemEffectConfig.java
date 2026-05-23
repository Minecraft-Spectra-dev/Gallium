package cn.spectra.gallium.glowoutline;

import java.util.List;

public record ItemEffectConfig(
        String shader,
        List<ShaderParam> params
) {
}
