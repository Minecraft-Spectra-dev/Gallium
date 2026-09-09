package cn.spectra.gallium.glowoutline.mixin;

//#if MC==1_21_11 || MC==1_26_01
import cn.spectra.gallium.glowoutline.shader.DepthMinPoolPipeline;
import cn.spectra.gallium.glowoutline.shader.DepthResamplePipeline;
import cn.spectra.gallium.glowoutline.shader.WorldMaskOcclusionPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
//#if MC>=1_21_11
import net.minecraft.resources.Identifier;
//#else
//$$ import net.minecraft.resources.ResourceLocation;
//#endif
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import static org.junit.jupiter.api.Assertions.*;

class InternalDepthShaderSourceMixinTest {
    private final InternalDepthShaderSourceMixin resolver = new InternalDepthShaderSourceMixin();

    private CallbackInfoReturnable<String> resolve(String namespace, String path, ShaderType type) {
        var callback = new CallbackInfoReturnable<String>("getShaderSource", true);
        //#if MC>=1_21_11
        resolver.galliumInternalDepthSource(Identifier.fromNamespaceAndPath(namespace, path), type, callback);
        //#else
        //$$ resolver.galliumInternalDepthSource(ResourceLocation.fromNamespaceAndPath(namespace, path), type, callback);
        //#endif
        return callback;
    }

    @Test
    void cacheMissResolvesBothStagesBeforePrecompileAndAfterPipelineDisposal() throws Exception {
        var vertex = resolve("gallium", "internal/depth_resample", ShaderType.VERTEX);
        var fragment = resolve("gallium", "internal/depth_resample", ShaderType.FRAGMENT);
        assertTrue(vertex.isCancelled());
        assertTrue(fragment.isCancelled());
        assertTrue(vertex.getReturnValue().contains("gl_Position = vec4(p - 1.0, 0.0, 1.0)"));
        assertTrue(fragment.getReturnValue().contains("gl_FragDepth = texture(Source, v_uv).r"));

        // The resolver must not depend on pipeline readiness or retain a compiled GPU object.
        var dispose = DepthResamplePipeline.class.getDeclaredMethod("dispose");
        dispose.setAccessible(true);
        dispose.invoke(null);
        assertFalse(DepthResamplePipeline.isReady());
        assertEquals(vertex.getReturnValue(), resolve(
                "gallium", "internal/depth_resample", ShaderType.VERTEX).getReturnValue());
        assertEquals(fragment.getReturnValue(), resolve(
                "gallium", "internal/depth_resample", ShaderType.FRAGMENT).getReturnValue());
    }

    @Test
    void minPoolCacheMissResolvesOriginalStagesBeforePrecompileAndAfterDisposal() throws Exception {
        var vertex = resolve("gallium", "internal/depth_minpool", ShaderType.VERTEX);
        var fragment = resolve("gallium", "internal/depth_minpool", ShaderType.FRAGMENT);
        assertTrue(vertex.isCancelled());
        assertTrue(fragment.isCancelled());
        var originalVertex = DepthMinPoolPipeline.class.getDeclaredField("VERTEX_SHADER");
        var originalFragment = DepthMinPoolPipeline.class.getDeclaredField("FRAGMENT_SHADER");
        originalVertex.setAccessible(true);
        originalFragment.setAccessible(true);
        assertEquals(originalVertex.get(null), vertex.getReturnValue());
        assertEquals(originalFragment.get(null), fragment.getReturnValue());
        assertTrue(fragment.getReturnValue().contains("m = max(m, texelFetch(Source, samplePixel, 0).r)"));
        assertTrue(fragment.getReturnValue().contains("gl_FragDepth = m"));

        var dispose = DepthMinPoolPipeline.class.getDeclaredMethod("dispose");
        dispose.setAccessible(true);
        dispose.invoke(null);
        assertFalse(DepthMinPoolPipeline.isReady());
        assertEquals(vertex.getReturnValue(), resolve(
                "gallium", "internal/depth_minpool", ShaderType.VERTEX).getReturnValue());
        assertEquals(fragment.getReturnValue(), resolve(
                "gallium", "internal/depth_minpool", ShaderType.FRAGMENT).getReturnValue());
    }

    @Test
    void nativeCoverageAndSourceVisibilityHaveStatelessPrivateSources() throws Exception {
        var vertex = resolve("gallium", "internal/world_mask_resolve", ShaderType.VERTEX);
        var fragment = resolve("gallium", "internal/world_mask_resolve", ShaderType.FRAGMENT);
        assertTrue(vertex.isCancelled());
        assertTrue(fragment.isCancelled());
        assertTrue(vertex.getReturnValue().contains("v_uv = p * 0.5"));
        assertTrue(fragment.getReturnValue().contains("fragColor = texture(NativeColor, v_uv)"));
        assertTrue(fragment.getReturnValue().contains("? texture(SourceDepth, v_uv).r : texture(NativeDepth, v_uv).r"));
        var prepare = resolve("gallium", "internal/world_mask_prepare", ShaderType.FRAGMENT);
        assertTrue(prepare.isCancelled());
        assertTrue(prepare.getReturnValue().contains("texture(SourceColor, v_uv).a > 0.0"));
        assertTrue(prepare.getReturnValue().contains("ownVisible ? 1.0 : texture(SceneDepth, v_uv).r"));
        assertFalse(fragment.getReturnValue().contains("discard"));
        assertFalse(fragment.getReturnValue().contains("bias"));
        var dispose = WorldMaskOcclusionPipeline.class.getDeclaredMethod("dispose");
        dispose.setAccessible(true);
        dispose.invoke(null);
        assertFalse(WorldMaskOcclusionPipeline.isReady());
        assertEquals(fragment.getReturnValue(), resolve(
                "gallium", "internal/world_mask_resolve", ShaderType.FRAGMENT).getReturnValue());
        assertEquals(prepare.getReturnValue(), resolve(
                "gallium", "internal/world_mask_prepare", ShaderType.FRAGMENT).getReturnValue());
        assertFalse(WorldMaskOcclusionPipeline.prepareNativeDepth(null, null, null, null, null));
        assertFalse(WorldMaskOcclusionPipeline.resolve(null, null, null, null, null, null, null));
        assertFalse(resolve("minecraft", "internal/world_mask_resolve", ShaderType.FRAGMENT).isCancelled());
        assertFalse(resolve("gallium", "internal/world_mask_prepare_extra", ShaderType.FRAGMENT).isCancelled());
    }

    @Test
    void publicPackAndOtherNamespaceSourcesRemainOwnedByShaderManager() {
        assertFalse(resolve("gallium", "core/glow_outline", ShaderType.FRAGMENT).isCancelled());
        assertFalse(resolve("gallium", "core/glow_outline_alt", ShaderType.FRAGMENT).isCancelled());
        assertFalse(resolve("minecraft", "internal/depth_resample", ShaderType.VERTEX).isCancelled());
        assertFalse(resolve("gallium", "internal/depth_resample_extra", ShaderType.FRAGMENT).isCancelled());
        assertFalse(resolve("minecraft", "internal/depth_minpool", ShaderType.VERTEX).isCancelled());
        assertFalse(resolve("gallium", "internal/depth_minpool_extra", ShaderType.FRAGMENT).isCancelled());
        assertFalse(resolve("gallium", "core/depth_minpool", ShaderType.FRAGMENT).isCancelled());
    }
}
//#endif
