package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureManager;
import cn.spectra.gallium.glowoutline.capture.GlowCaptureState;
//#if MC>=1_21_05
import com.mojang.blaze3d.pipeline.RenderPipeline;
//#endif
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
//#if MC>=1_21_05
import com.mojang.blaze3d.systems.RenderPass;
//#endif
import com.mojang.blaze3d.systems.RenderSystem;
//#if MC<1_21_05
//$$ import com.mojang.blaze3d.vertex.BufferBuilder;
//$$ import com.mojang.blaze3d.vertex.BufferUploader;
//$$ import com.mojang.blaze3d.vertex.DefaultVertexFormat;
//$$ import com.mojang.blaze3d.vertex.Tesselator;
//$$ import com.mojang.blaze3d.vertex.VertexFormat;
//#if MC>=1_21_02
//$$ import net.minecraft.client.renderer.CompiledShaderProgram;
//#else
//$$ import com.mojang.blaze3d.vertex.VertexSorting;
//$$ import net.minecraft.client.renderer.ShaderInstance;
//#endif
//#endif
//#if MC>=1_21_05
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
//#endif
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
        //#elseif MC>=1_21_05
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
        //#else
        //$$ int w = mainTarget.width;
        //$$ int h = mainTarget.height;
        //$$ if (tempColorTarget == null || tempColorTarget.width != w || tempColorTarget.height != h) {
        //$$     if (tempColorTarget != null) tempColorTarget.destroyBuffers();
//#if MC>=1_21_02
        //$$     tempColorTarget = new TextureTarget(w, h, false);
//#else
        //$$     tempColorTarget = new TextureTarget(w, h, false, net.minecraft.client.Minecraft.ON_OSX);
//#endif
        //$$ }
        //$$ com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(36008, mainTarget.frameBufferId);
        //$$ com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(36009, tempColorTarget.frameBufferId);
        //$$ com.mojang.blaze3d.platform.GlStateManager._glBlitFrameBuffer(0, 0, w, h, 0, 0, w, h, 16384, 9728);
        //$$ com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(36160, 0);
        //$$ try {
        //$$     for (GlowCaptureState state : GlowCaptureManager.getActiveStates()) {
        //$$         drawGlow(state, minecraft, mainTarget);
        //$$     }
        //$$ } finally {
        //$$     // Unconditionally restore mainTarget binding before returning to vanilla.
        //$$     // The blit above ends with FB=0 (default framebuffer); each drawGlow that
        //$$     // *succeeds* leaves mainTarget bound, but a drawGlow that returns early
        //$$     // (e.g. program failed to compile, or capturedThisFrame was false) does
        //$$     // not. Without this finally the next vanilla call after composite —
        //$$     // GameRenderer.render's RenderSystem.clear(256) — would clear the default
        //$$     // framebuffer instead of mainTarget, leaving stale world depth on
        //$$     // mainTarget and corrupting the GUI pass that follows.
        //$$     mainTarget.bindWrite(true);
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
    //#elseif MC>=1_21_05
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
    //#else
    //$$ // Pre-1.21.5 legacy GL composite. Covers 1.21.2–1.21.4 (CompiledShaderProgram path) and
    //$$ // 1.21.1 (ShaderInstance path) — the two diverge only at the program type, the sampler
    //$$ // bind method name, the setShader overload, and the projection-type enum. Each difference
    //$$ // is a small nested //#if MC>=1_21_02 gate; the surrounding GL state setup, texture filter
    //$$ // params, sampler routing, and screen-quad geometry are identical across the two.
    //$$ private static void drawGlow(GlowCaptureState state, Minecraft minecraft, RenderTarget mainTarget) {
    //$$     if (!state.capturedThisFrame || state.config == null || state.maskTarget == null) return;
    //$$     GlowCaptureManager.renderCapturedNodes(state, minecraft);
    //$$     TextureTarget mask = state.maskTarget;
    //#if MC>=1_21_02
    //$$     CompiledShaderProgram program = GlowPipeline.getOrCreate(state.config);
    //#else
    //$$     ShaderInstance program = GlowPipeline.getOrCreate(state.config);
    //#endif
    //$$     if (program == null || tempColorTarget == null) return;
    //$$
    //$$     int w = mainTarget.width;
    //$$     int h = mainTarget.height;
    //$$     // SceneDepthSampler choice mirrors selectSceneDepthView() on 1.21.6+:
    //$$     //   firstPerson    → mask.depth (self-compare; outline never occluded by world)
    //$$     //   Iris active    → sceneDepthTarget (pre-clear world depth — Iris rewrites
    //$$     //                    mainTarget.depth with shader-pack post-process output, so
    //$$     //                    its z is no longer comparable to vanilla item z)
    //$$     //   else (no Iris) → mainTarget.depth at composite time. By this point
    //$$     //                    GameRenderer.renderLevel has cleared the world depth and
    //$$     //                    overwritten it with the held-item depth, so this captures
    //$$     //                    "what's in front" including the player's own hand. Pack shaders
    //$$     //                    use this to occlude world-item outlines behind the held item.
    //$$     int sceneDepth;
    //$$     if (state.firstPerson) {
    //$$         sceneDepth = mask.getDepthTextureId();
    //$$     } else if (IrisCompat.isShaderActive()
    //$$             && GlowCaptureManager.getSceneDepthTarget() != null) {
    //$$         sceneDepth = GlowCaptureManager.getSceneDepthTarget().getDepthTextureId();
    //$$     } else {
    //$$         sceneDepth = mainTarget.getDepthTextureId();
    //$$     }
    //$$
    //$$     setTextureLinear(tempColorTarget.getColorTextureId());
    //$$     setTextureNearest(mask.getColorTextureId());
    //$$     setTextureNearest(mask.getDepthTextureId());
    //$$     setTextureNearest(sceneDepth);
    //$$
    //#if MC>=1_21_02
    //$$     program.bindSampler("DiffuseSampler", tempColorTarget.getColorTextureId());
    //$$     program.bindSampler("MaskSampler", mask.getColorTextureId());
    //$$     program.bindSampler("MaskDepthSampler", mask.getDepthTextureId());
    //$$     program.bindSampler("SceneDepthSampler", sceneDepth);
    //#else
    //$$     program.setSampler("DiffuseSampler", tempColorTarget.getColorTextureId());
    //$$     program.setSampler("MaskSampler", mask.getColorTextureId());
    //$$     program.setSampler("MaskDepthSampler", mask.getDepthTextureId());
    //$$     program.setSampler("SceneDepthSampler", sceneDepth);
    //#endif
    //$$     program.safeGetUniform("FrameTimeCounter").set(GlowTime.worldSecondsFloat());
    //$$     program.safeGetUniform("ScreenSize").set((float) w, (float) h);
    //$$     float maskScale = state.lastMaskScale;
    //$$     program.safeGetUniform("ShaderAlign").set(maskScale, maskScale, 0.0f, 0.0f);
    //$$     for (cn.spectra.gallium.glowoutline.ShaderParam p : state.config.params()) {
    //$$         switch (p) {
    //$$             case cn.spectra.gallium.glowoutline.ShaderParam.Float f -> program.safeGetUniform(f.name()).set(f.value());
    //$$             case cn.spectra.gallium.glowoutline.ShaderParam.Vec2 v -> program.safeGetUniform(v.name()).set(v.x(), v.y());
    //$$             case cn.spectra.gallium.glowoutline.ShaderParam.Vec3 v -> program.safeGetUniform(v.name()).set(v.x(), v.y(), v.z());
    //$$             case cn.spectra.gallium.glowoutline.ShaderParam.Vec4 v -> program.safeGetUniform(v.name()).set(v.x(), v.y(), v.z(), v.w());
    //$$         }
    //$$     }
    //$$
    //$$     mainTarget.bindWrite(true);
    //$$     RenderSystem.disableDepthTest();
    //$$     RenderSystem.depthMask(false);
    //$$     RenderSystem.disableCull();
    //$$     RenderSystem.enableBlend();
    //$$     RenderSystem.blendFuncSeparate(1, 1, 1, 0);
    //#if MC>=1_21_02
    //$$     RenderSystem.setShader(program);
    //#else
    //$$     final ShaderInstance shaderRef = program;
    //$$     RenderSystem.setShader(() -> shaderRef);
    //#endif
    //$$     RenderSystem.backupProjectionMatrix();
    //$$     // Wide ortho z range so z=500 is comfortably inside the clip volume regardless of
    //$$     // JOML setOrtho convention quirks. Near=-1000, far=3000 gives view-z range that
    //$$     // straddles 0; vertex z=500 maps to roughly NDC.z=-0.75, well inside [-1, 1].
    //#if MC>=1_21_02
    //$$     RenderSystem.setProjectionMatrix(new org.joml.Matrix4f().setOrtho(0.0f, (float) w, (float) h, 0.0f, -1000.0f, 3000.0f), com.mojang.blaze3d.ProjectionType.ORTHOGRAPHIC);
    //#else
    //$$     RenderSystem.setProjectionMatrix(new org.joml.Matrix4f().setOrtho(0.0f, (float) w, (float) h, 0.0f, -1000.0f, 3000.0f), VertexSorting.ORTHOGRAPHIC_Z);
    //#endif
    //$$     RenderSystem.getModelViewStack().pushMatrix();
    //$$     RenderSystem.getModelViewStack().identity();
    //$$     try {
    //$$         BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
    //$$         bb.addVertex(0.0f, 0.0f, 500.0f);
    //$$         bb.addVertex((float) w, 0.0f, 500.0f);
    //$$         bb.addVertex((float) w, (float) h, 500.0f);
    //$$         bb.addVertex(0.0f, (float) h, 500.0f);
    //$$         BufferUploader.drawWithShader(bb.buildOrThrow());
    //$$     } finally {
    //$$         RenderSystem.getModelViewStack().popMatrix();
    //$$         RenderSystem.restoreProjectionMatrix();
    //$$         RenderSystem.defaultBlendFunc();
    //$$         RenderSystem.disableBlend();
    //$$         RenderSystem.enableCull();
    //$$         RenderSystem.depthMask(true);
    //$$         RenderSystem.enableDepthTest();
    //$$     }
    //$$ }
    //$$ private static void setTextureLinear(int id) { setTextureParams(id, 9729); }
    //$$ private static void setTextureNearest(int id) { setTextureParams(id, 9728); }
    //$$ private static void setTextureParams(int id, int filter) {
    //$$     RenderSystem.bindTexture(id);
    //$$     com.mojang.blaze3d.platform.GlStateManager._texParameter(3553, 10241, filter);
    //$$     com.mojang.blaze3d.platform.GlStateManager._texParameter(3553, 10240, filter);
    //$$     com.mojang.blaze3d.platform.GlStateManager._texParameter(3553, 10242, 33071);
    //$$     com.mojang.blaze3d.platform.GlStateManager._texParameter(3553, 10243, 33071);
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
