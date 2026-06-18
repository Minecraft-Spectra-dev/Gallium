package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureManager;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * Owns the GPU resources used for the world-space glow composite pass.
 * Centralizing them here lets {@link GlowResources} dispose them on resource reload.
 */
public final class GlowComposite {

    @Nullable private static TextureTarget tempColorTarget;
    @Nullable private static GlowUniformBuffer uniformBuffer;

    static {
        GlowResources.register(GlowComposite::dispose);
    }

    private GlowComposite() {}

    public static boolean hasAnyValidCapture() {
        for (GlowCaptureState state : GlowCaptureManager.getActiveStates()) {
            if (state.capturedThisFrame && state.config != null && state.maskTarget != null) return true;
        }
        return false;
    }

    public static void composite(Minecraft minecraft, RenderTarget mainTarget) {
        //#if MC>=1_21_06
        int w = mainTarget.width;
        int h = mainTarget.height;

        if (tempColorTarget == null || tempColorTarget.width != w || tempColorTarget.height != h) {
            if (tempColorTarget != null) tempColorTarget.destroyBuffers();
            tempColorTarget = new TextureTarget("GlowColor", w, h, false);
            //#if MC<1_21_11
            //$$ // See GlowCaptureManager: 1.21.10 sampler completeness needs useMipmaps=false on
            //$$ // single-mip render targets. 1.21.11+ moved this off GpuTexture entirely.
            //$$ tempColorTarget.getColorTexture().setUseMipmaps(false);
            //#endif
        }
        if (uniformBuffer == null) uniformBuffer = new GlowUniformBuffer("Glow Uniform Buffer");

        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
                mainTarget.getColorTexture(), tempColorTarget.getColorTexture(),
                0, 0, 0, 0, 0, w, h);

        for (GlowCaptureState state : GlowCaptureManager.getActiveStates()) {
            drawGlow(state, minecraft, mainTarget);
        }
        //#else
        //$$ // 1.21.5: GpuTexture-based path — no GpuTextureView / SamplerHelper / GlowUniformBuffer.
        //$$ int w = mainTarget.width;
        //$$ int h = mainTarget.height;
        //$$
        //$$ if (tempColorTarget == null || tempColorTarget.width != w || tempColorTarget.height != h) {
        //$$     if (tempColorTarget != null) tempColorTarget.destroyBuffers();
        //$$     tempColorTarget = new TextureTarget("GlowColor", w, h, false);
        //$$ }
        //$$
        //$$ RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(
        //$$         mainTarget.getColorTexture(), tempColorTarget.getColorTexture(),
        //$$         0, 0, 0, 0, 0, w, h);
        //$$
        //$$ for (GlowCaptureState state : GlowCaptureManager.getActiveStates()) {
        //$$     drawGlow(state, minecraft, mainTarget);
        //$$ }
        //#endif
    }

    //#if MC>=1_21_06
    private static void drawGlow(GlowCaptureState state, Minecraft minecraft, RenderTarget mainTarget) {
        if (!state.capturedThisFrame || state.config == null || state.maskTarget == null) return;

        GlowCaptureManager.renderCapturedNodes(state, minecraft);

        TextureTarget mask = state.maskTarget;
        if (mask.getColorTextureView() == null || mask.getDepthTextureView() == null) return;

        RenderPipeline pipeline = GlowPipeline.get(state.config.shader());
        if (pipeline == null) return;

        int w = mainTarget.width;
        int h = mainTarget.height;
        float maskScale = state.lastMaskScale;
        uniformBuffer.update(GlowTime.worldSecondsFloat(), w, h,
                maskScale, maskScale, state.config);

        com.mojang.blaze3d.textures.GpuTextureView sceneDepthView = selectSceneDepthView(state, mask, mainTarget);
        FilterMode maskFilter = maskScale < 1.0f ? FilterMode.LINEAR : FilterMode.NEAREST;

        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "Glow", mainTarget.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(pipeline);
            pass.setUniform("GlowUniforms", uniformBuffer.getSlice());
            SamplerHelper.bindClampToEdge(pass, "DiffuseSampler",
                    tempColorTarget.getColorTextureView(), FilterMode.LINEAR);
            SamplerHelper.bindClampToEdge(pass, "MaskSampler",
                    mask.getColorTextureView(), maskFilter);
            SamplerHelper.bindClampToEdge(pass, "MaskDepthSampler",
                    mask.getDepthTextureView(), FilterMode.NEAREST);
            SamplerHelper.bindClampToEdge(pass, "SceneDepthSampler",
                    sceneDepthView, FilterMode.NEAREST);
            //#if MC<1_21_09
            //$$ pass.setVertexBuffer(0, RenderSystem.getQuadVertexBuffer());
            //#endif
            pass.draw(0, 3);
        }
    }
    //#else
    //$$ // 1.21.5: GpuTexture path — no GpuTextureView / SamplerHelper / GlowUniformBuffer.
    //$$ private static void drawGlow(GlowCaptureState state, Minecraft minecraft, RenderTarget mainTarget) {
    //$$     if (!state.capturedThisFrame || state.config == null || state.maskTarget == null) return;
    //$$
    //$$     GlowCaptureManager.renderCapturedNodes(state, minecraft);
    //$$
    //$$     TextureTarget mask = state.maskTarget;
    //$$     if (mask.getColorTexture() == null || mask.getDepthTexture() == null) return;
    //$$
    //$$     RenderPipeline pipeline = GlowPipeline.get(state.config);
    //$$     if (pipeline == null) return;
    //$$
    //$$     int w = mainTarget.width;
    //$$     int h = mainTarget.height;
    //$$     float maskScale = state.lastMaskScale; // always 1.0 on 1.21.5
    //$$
    //$$     // Set up sampler state directly on GpuTexture (1.21.5 API)
    //$$     var diffTex = tempColorTarget.getColorTexture();
    //$$     diffTex.setTextureFilter(FilterMode.LINEAR, false);
    //$$     diffTex.setAddressMode(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE);
    //$$
    //$$     var maskTex = mask.getColorTexture();
    //$$     maskTex.setTextureFilter(FilterMode.NEAREST, false);
    //$$     maskTex.setAddressMode(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE);
    //$$
    //$$     var maskDepthTex = mask.getDepthTexture();
    //$$     maskDepthTex.setTextureFilter(FilterMode.NEAREST, false);
    //$$     maskDepthTex.setAddressMode(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE);
    //$$
    //$$     GpuTexture sceneDepthTex;
    //$$     if (state.firstPerson) {
    //$$         sceneDepthTex = mask.getDepthTexture();
    //$$     } else {
    //$$         // Always prefer the early-captured sceneDepthTarget. On 1.21.5,
    //$$         // GameRenderer.renderLevel clears mainTarget.getDepthTexture() to 1.0 right after
    //$$         // levelRenderer.renderLevel and before renderItemInHand — so by the time this
    //$$         // composite TAIL runs, mainTarget depth is the cleared (1.0) + held-item depth,
    //$$         // not world depth. Comparing item z against 1.0 makes step(itemDepth, 1.0) always
    //$$         // 1.0, so every item — even ones behind walls — would get an outline. The
    //$$         // captureSceneDepth hook fires at exactly that clearDepthTexture, so
    //$$         // sceneDepthTarget holds the pre-clear world depth and is the correct source for
    //$$         // both the no-shader and Iris-active occlusion tests. The mask fallback is only
    //$$         // for the (unexpected) case where capture never ran this frame.
    //$$         TextureTarget sd = GlowCaptureManager.getSceneDepthTarget();
    //$$         sceneDepthTex = sd != null ? sd.getDepthTexture() : mask.getDepthTexture();
    //$$     }
    //$$     sceneDepthTex.setTextureFilter(FilterMode.NEAREST, false);
    //$$     sceneDepthTex.setAddressMode(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE);
    //$$
    //$$     try (RenderPass pass = RenderSystem.getDevice()
    //$$             .createCommandEncoder()
    //$$             .createRenderPass(mainTarget.getColorTexture(), OptionalInt.empty())) {
    //$$         pass.setPipeline(pipeline);
    //$$         pass.bindSampler("DiffuseSampler", diffTex);
    //$$         pass.bindSampler("MaskSampler", maskTex);
    //$$         pass.bindSampler("MaskDepthSampler", maskDepthTex);
    //$$         pass.bindSampler("SceneDepthSampler", sceneDepthTex);
    //$$         // 1.21.5: individual uniforms instead of UBO
    //$$         pass.setUniform("FrameTimeCounter", GlowTime.worldSecondsFloat());
    //$$         pass.setUniform("ScreenSize", (float) w, (float) h);
    //$$         pass.setUniform("ShaderAlign", maskScale, maskScale, 0.0f, 0.0f);
    //$$         for (cn.spectra.gallium.glowoutline.ShaderParam p : state.config.params()) {
    //$$             switch (p) {
    //$$                 case cn.spectra.gallium.glowoutline.ShaderParam.Float f2 ->
    //$$                     pass.setUniform(f2.name(), f2.value());
    //$$                 case cn.spectra.gallium.glowoutline.ShaderParam.Vec2 v2 ->
    //$$                     pass.setUniform(v2.name(), v2.x(), v2.y());
    //$$                 case cn.spectra.gallium.glowoutline.ShaderParam.Vec3 v3 ->
    //$$                     pass.setUniform(v3.name(), v3.x(), v3.y(), v3.z());
    //$$                 case cn.spectra.gallium.glowoutline.ShaderParam.Vec4 v4 ->
    //$$                     pass.setUniform(v4.name(), v4.x(), v4.y(), v4.z(), v4.w());
    //$$             }
    //$$         }
    //$$         pass.setVertexBuffer(0, RenderSystem.getQuadVertexBuffer());
    //$$         pass.draw(0, 3);
    //$$     }
    //$$ }
    //#endif

    //#if MC>=1_21_06
    private static com.mojang.blaze3d.textures.GpuTextureView selectSceneDepthView(
            GlowCaptureState state, TextureTarget mask, RenderTarget mainTarget) {
        // First-person uses mask self-compare: hud3d projection captured at the hand pass
        // doesn't match the level/entity projection used for sceneDepthTarget, so depth
        // comparison would be meaningless. Self-compare = no world occlusion in first-
        // person, but first-person doesn't show the player's own body anyway.
        if (state.firstPerson) return mask.getDepthTextureView();
        if (!IrisCompat.isShaderActive()) return mainTarget.getDepthTextureView();
        TextureTarget sceneDepth = GlowCaptureManager.getSceneDepthTarget();
        return sceneDepth != null ? sceneDepth.getDepthTextureView() : mask.getDepthTextureView();
    }
    //#endif

    private static void dispose() {
        if (tempColorTarget != null) {
            tempColorTarget.destroyBuffers();
            tempColorTarget = null;
        }
        if (uniformBuffer != null) {
            uniformBuffer.close();
            uniformBuffer = null;
        }
    }
}
