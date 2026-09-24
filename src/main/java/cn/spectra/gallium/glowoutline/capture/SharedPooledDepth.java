package cn.spectra.gallium.glowoutline.capture;

//#if MC==1_21_08 || MC==1_21_10 || MC==1_21_11 || MC==1_26_01
import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.shader.DepthMinPoolPipeline;
import cn.spectra.gallium.glowoutline.shader.GlowResources;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;

/** One immutable pooled scene snapshot for ordinary shared-mask replays. */
final class SharedPooledDepth {
    private static TextureTarget target;
    private static GpuTexture sourceDepth;
    private static GlowCaptureState preparedMask;
    //#if MC==1_26_01
    private static boolean seedBitsReady;
    //#endif
    private static long epoch = Long.MIN_VALUE, generation = Long.MIN_VALUE;
    static { GlowResources.register(SharedPooledDepth::dispose); }
    private SharedPooledDepth() {}

    static void beginFrame(boolean ordinary, int width, int height) {
        preparedMask = null;
        if (target != null && (!ordinary || !IrisCompat.isShaderActive()
                || IrisCompat.isActiveSrRuntime() || target.width != width || target.height != height))
            dispose();
    }

    static TextureTarget prepare(CommandEncoder encoder, TextureTarget source,
                                 long currentEpoch, long currentGeneration) {
        if (source == null || !SharedMaskFrame.current() || !IrisCompat.isShaderActive()
                || IrisCompat.isActiveSrRuntime() || !DepthMinPoolPipeline.isReady()
                || !SharedMaskFrame.targetMatches(source, source.width, source.height)) return null;
        // Stored masks and their scalar fallback can coexist with this snapshot.
        if (!GlowCaptureManager.pooledDepthFitsBudget(source.width, source.height))
            return null;
        if (target != null && !SharedMaskFrame.targetMatches(target, source.width, source.height)) dispose();
        if (target == null) {
            target = new TextureTarget("GlowPooledSceneDepth", source.width, source.height, true);
            //#if MC<1_21_11
            //$$ target.getDepthTexture().setUseMipmaps(false);
            //$$ target.getColorTexture().setUseMipmaps(false);
            //#endif
        }
        if (sourceDepth == source.getDepthTexture() && epoch == currentEpoch && generation == currentGeneration)
            return target;
        sourceDepth = null; epoch = generation = Long.MIN_VALUE;
        //#if MC==1_26_01
        seedBitsReady = NativePoolSeedBits.pool(encoder, source, target);
        if (!seedBitsReady)
        //#endif
        if (!DepthMinPoolPipeline.pool(encoder, source.getDepthTextureView(),
                target.getColorTextureView(), target.getDepthTextureView())) return null;
        sourceDepth = source.getDepthTexture(); epoch = currentEpoch; generation = currentGeneration;
        return target;
    }

    /** Only the just-replayed mask may acquire this immutable base for deferred storage. */
    static void preparedMask(GlowCaptureState state, TextureTarget base) {
        preparedMask = base != null && base == target ? state : null;
    }

    static TextureTarget maskBase(GlowCaptureState state, TextureTarget source,
                                  long currentEpoch, long currentGeneration) {
        return state != null && state == preparedMask && !state.firstPerson
                && state.maskDepthPrepared && state.maskDepthSnapshotGeneration == currentGeneration
                && SharedMaskFrame.current() && IrisCompat.isShaderActive() && !IrisCompat.isActiveSrRuntime()
                && source != null && sourceDepth == source.getDepthTexture()
                && epoch == currentEpoch && generation == currentGeneration && target != null
                && SharedMaskFrame.targetMatches(target, source.width, source.height) ? target : null;
    }

    //#if MC==1_26_01
    static com.mojang.blaze3d.textures.GpuTextureView seedBits(TextureTarget source) {
        return seedBitsReady && source == target && sourceDepth != null && SharedMaskFrame.current()
                && epoch == cn.spectra.gallium.glowoutline.SuperResolutionCompat.currentFrameEpoch()
                && generation == GlowCaptureManager.getSceneDepthGeneration()
                ? target.getColorTextureView() : null;
    }
    //#endif

    static long reservedBytes() {
        return target == null ? 0L : GlowCaptureManager.estimatedCaptureTargetBytes(
                target.width, target.height, GlowCaptureManager.CAPTURE_TARGET_BYTES_PER_PIXEL);
    }

    static void dispose() {
        var retired = target; target = null; sourceDepth = null; preparedMask = null;
        epoch = generation = Long.MIN_VALUE;
        //#if MC==1_26_01
        seedBitsReady = false;
        //#endif
        if (retired != null) retired.destroyBuffers();
    }
}
//#else
//$$ final class SharedPooledDepth { private SharedPooledDepth() {} }
//#endif
