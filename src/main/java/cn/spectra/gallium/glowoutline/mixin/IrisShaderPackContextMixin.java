package cn.spectra.gallium.glowoutline.mixin;

import cn.spectra.gallium.glowoutline.sr.SrShaderPackContext;
import cn.spectra.gallium.glowoutline.sr.SrShaderPackResolver;
import cn.spectra.gallium.glowoutline.sr.SrShaderPackResolver.Session;
import com.google.common.collect.ImmutableList;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures Iris's real {@code shaders/} path before an owned zip filesystem can disappear. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.ShaderPack", remap = false)
public abstract class IrisShaderPackContextMixin implements SrShaderPackContext {

    @Unique private Optional<Session> gallium$srSession = Optional.empty();
    @Unique private Optional<String> gallium$hint = Optional.empty();
    @Unique private Optional<Map<String, String>> gallium$environmentDefines = Optional.empty();
    @Unique private Path gallium$shadersPath;
    @Unique private boolean gallium$contextCaptured;

    /** Save the caller-provided list before Iris appends its internal replacement defines. */
    @Inject(
            method = "<init>(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;Z)V",
            // Mixin 0.8.7 forbids a normal constructor HEAD callback before super(). CTOR_HEAD
            // selects the first safe point after delegate + field initializers but before Iris's
            // constructor body replaces environmentDefines.
            at = @At(value = "CTOR_HEAD", unsafe = true),
            require = 0,
            remap = false)
    private void gallium$captureOriginalShaderPackArguments(
            Path shadersPath,
            Map<?, ?> changedConfigs,
            ImmutableList<?> environmentDefines,
            boolean zip,
            CallbackInfo ci) {
        gallium$shadersPath = SrShaderPackResolver.normalizeShadersPath(shadersPath);
        gallium$environmentDefines =
                SrShaderPackResolver.tryCopyEnvironmentDefines(environmentDefines);
    }

    /**
     * Every supported Iris build funnels its three-argument constructor through this overload.
     * {@code require=0} leaves Gallium loadable if a future Iris changes the constructor ABI.
     */
    @Inject(
            method = "<init>(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;Z)V",
            at = @At("RETURN"),
            require = 0,
            remap = false)
    private void gallium$captureShaderPackContext(
            Path shadersPath,
            Map<?, ?> changedConfigs,
            ImmutableList<?> environmentDefines,
            boolean zip,
        CallbackInfo ci) {
        if (gallium$contextCaptured) return;

        Path actualShadersPath = gallium$shadersPath;
        if (actualShadersPath == null && shadersPath != null) {
            actualShadersPath = SrShaderPackResolver.normalizeShadersPath(shadersPath);
        }
        try {
            gallium$srSession = gallium$environmentDefines.isPresent()
                    ? SrShaderPackResolver.load(
                            actualShadersPath, this, gallium$environmentDefines.get())
                    : SrShaderPackResolver.load(actualShadersPath, this);
        } catch (Throwable ignored) {
            // Keep captured=false so ShaderPackHint may use its directory fallback.
            return;
        }

        Path packRoot = SrShaderPackResolver.packRootForShaders(actualShadersPath).orElse(null);
        if (packRoot == null) return;
        Path hintPath = packRoot.resolve("gallium.json");
        try {
            if (Files.isRegularFile(hintPath)) {
                gallium$hint = Optional.of(Files.readString(hintPath, StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
            // ShaderPackHint retains its normal fail-closed fallback and diagnostic path.
        }
        gallium$contextCaptured = true;
    }

    @Override
    public boolean gallium$hasCapturedShaderPackContext() {
        return gallium$contextCaptured;
    }

    @Override
    public Optional<Session> gallium$getSrDefinitionSession() {
        return gallium$srSession;
    }

    @Override
    public Optional<String> gallium$getGalliumHint() {
        return gallium$hint;
    }
}
