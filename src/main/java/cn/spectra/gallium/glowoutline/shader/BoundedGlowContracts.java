package cn.spectra.gallium.glowoutline.shader;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Internal bounds derived only from verified shader sources and the actual linked program. */
final class BoundedGlowContracts {
    private static final Map<String, Optional<BoundedGlowContract>> cache = new HashMap<>();
    private static final Set<String> automatic = new HashSet<>();
    static { GlowResources.register(() -> { cache.clear(); automatic.clear(); }); }
    private BoundedGlowContracts() {}

    static BoundedGlowContract get(String shader) {
        if (cache.size() >= 512 && !cache.containsKey(shader)) { cache.clear(); automatic.clear(); }
        var result = cache.computeIfAbsent(shader, BoundedGlowContracts::verifySources).orElse(null);
        if (result != null && !GlowPipeline.matchesAutomaticContract(shader)) return null;
        return result;
    }

    static boolean automatic(String shader) { return automatic.contains(shader); }

    private static Optional<BoundedGlowContract> verifySources(String shader) {
        if (!OriginalGlowBundles.matches(shader)) return Optional.empty();
        automatic.add(shader);
        return Optional.of(new BoundedGlowContract(1f / 64f, 6, 2, .5f,
                //#if MC>=1_21_06 && MC<1_26_02
                true, true,
                //#else
                //$$ false, false,
                //#endif
                true));
    }
}
