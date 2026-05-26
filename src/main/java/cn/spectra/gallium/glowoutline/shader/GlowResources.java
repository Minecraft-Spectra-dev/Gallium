package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.Gallium;
import java.util.ArrayList;
import java.util.List;

/**
 * Central disposer for GPU-backed objects (RenderPipelines, TextureTargets, UBOs).
 * Anything that allocates VRAM should register an idempotent disposer here so
 * resource pack reloads can release it safely. Disposers stay registered across
 * reloads, so they must tolerate being invoked when no resource is currently held.
 * <p>
 * Two channels:
 * <ul>
 *   <li>Runtime — texture targets, UBOs, capture state. Disposed every reload.</li>
 *   <li>Pipeline — RenderPipelines that vanilla cannot {@code close()}. Disposed only on
 *       full teardown ({@link #disposeAll()}) so we don't leak driver state across reloads.
 *       Incremental pruning is done by the pipeline caches themselves via {@code retainOnly}.</li>
 * </ul>
 */
public final class GlowResources {

    private static final List<Runnable> runtimeDisposers = new ArrayList<>();
    private static final List<Runnable> pipelineDisposers = new ArrayList<>();

    private GlowResources() {}

    /** Register a disposer for short-lived GPU resources (mask targets, UBOs, capture buffers). */
    public static void register(Runnable disposer) {
        runtimeDisposers.add(disposer);
    }

    /** Register a disposer for pipeline caches. Only invoked on full teardown. */
    public static void registerPipeline(Runnable disposer) {
        pipelineDisposers.add(disposer);
    }

    /** Forces all registered disposer-owning classes to load so they can register themselves.
     *  Static-init registration is fragile if a class is never referenced; calling this from
     *  mod init ensures every disposer is wired up. */
    public static void eagerInit() {
        // Reference each class so its static initializer runs and registers a disposer.
        Class<?>[] classes = {
            cn.spectra.gallium.glowoutline.shader.GlowComposite.class,
            cn.spectra.gallium.glowoutline.shader.GlowPipeline.class,
            cn.spectra.gallium.glowoutline.shader.GuiGlowElementPipeline.class,
            cn.spectra.gallium.glowoutline.shader.GuiGlowRenderer.class,
            cn.spectra.gallium.glowoutline.capture.GlowCaptureManager.class,
        };
        for (Class<?> c : classes) {
            try { Class.forName(c.getName(), true, c.getClassLoader()); }
            catch (Throwable t) { Gallium.LOGGER.warn("eagerInit failed for {}: {}", c.getName(), t.toString()); }
        }
    }

    /** Dispose all runtime resources. Pipelines are kept; use {@link #disposeAll()} for full teardown. */
    public static void disposeRuntime() {
        runAll(runtimeDisposers);
    }

    /** Full teardown — runtime resources AND pipeline caches. */
    public static void disposeAll() {
        runAll(runtimeDisposers);
        runAll(pipelineDisposers);
    }

    private static void runAll(List<Runnable> disposers) {
        for (int i = disposers.size() - 1; i >= 0; i--) {
            try {
                disposers.get(i).run();
            } catch (Throwable t) {
                // Throwable, not Exception: a disposer that throws Error (OOM, native crash
                // surfaced as UnsatisfiedLinkError, etc.) must not abort the rest of the
                // chain — partial cleanup leaks GPU memory across reloads.
                Gallium.LOGGER.warn("Failed to dispose glow resource: {}", t.toString());
            }
        }
    }
}
