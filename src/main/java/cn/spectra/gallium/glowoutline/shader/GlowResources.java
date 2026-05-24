package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.Gallium;
import java.util.ArrayList;
import java.util.List;

/**
 * Central disposer for GPU-backed objects (RenderPipelines, TextureTargets, UBOs).
 * Anything that allocates VRAM should register an idempotent disposer here so
 * resource pack reloads can release it safely. Disposers stay registered across
 * reloads, so they must tolerate being invoked when no resource is currently held.
 */
public final class GlowResources {

    private static final List<Runnable> disposers = new ArrayList<>();

    private GlowResources() {}

    public static void register(Runnable disposer) {
        disposers.add(disposer);
    }

    public static void disposeAll() {
        for (int i = disposers.size() - 1; i >= 0; i--) {
            try {
                disposers.get(i).run();
            } catch (Exception e) {
                Gallium.LOGGER.warn("Failed to dispose glow resource: {}", e.toString());
            }
        }
    }
}
