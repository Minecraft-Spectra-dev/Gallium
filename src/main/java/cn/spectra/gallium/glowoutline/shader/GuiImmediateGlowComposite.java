package cn.spectra.gallium.glowoutline.shader;

//#if MC>=1_21_05
//#if MC<1_21_06
//$$ import cn.spectra.gallium.glowoutline.ItemEffectConfig;
//$$ import cn.spectra.gallium.glowoutline.ShaderParam;
//$$ import cn.spectra.gallium.glowoutline.capture.CaptureSites;
//$$ import com.mojang.blaze3d.pipeline.RenderPipeline;
//$$ import com.mojang.blaze3d.pipeline.RenderTarget;
//$$ import com.mojang.blaze3d.pipeline.TextureTarget;
//$$ import com.mojang.blaze3d.systems.RenderPass;
//$$ import com.mojang.blaze3d.systems.RenderSystem;
//$$ import com.mojang.blaze3d.textures.AddressMode;
//$$ import com.mojang.blaze3d.textures.FilterMode;
//$$ import com.mojang.blaze3d.vertex.BufferBuilder;
//$$ import com.mojang.blaze3d.vertex.ByteBufferBuilder;
//$$ import com.mojang.blaze3d.vertex.DefaultVertexFormat;
//$$ import com.mojang.blaze3d.vertex.VertexFormat;
//$$ import java.util.OptionalInt;
//$$ import net.minecraft.client.Minecraft;
//$$
//$$ /**
//$$  * Pre-1.21.6 (immediate-mode GUI rendering) per-item GUI glow composite. Called
//$$  * once per glowing GUI item from
//$$  * {@link cn.spectra.gallium.glowoutline.mixin.GuiGraphicsItemMixin} immediately after
//$$  * the vanilla item render completes.
//$$  *
//$$  * <p>Two stages:
//$$  * <ol>
//$$  *   <li>Replay the captured item mesh into {@link GuiImmediateGlowTile}'s small
//$$  *       off-screen FBO under a custom orthographic projection that turns the 3D item
//$$  *       silhouette into a 2D alpha tile — equivalent in shape and size to 1.21.6+'s
//$$  *       vanilla item atlas tile.</li>
//$$  *   <li>Draw a glow quad on mainTarget covering the item slot + 4-px GUI-px margin,
//$$  *       sampling the tile through {@code core/<shader>_gui.fsh}'s ring sampler. Same
//$$  *       shader, same uniforms, same algorithm as 1.21.6+ — produces the visually
//$$  *       identical thin alpha-edged outline.</li>
//$$  * </ol>
//$$  *
//$$  * <p>Per-item composite (rather than frame-end batched) gives correct z-order with
//$$  * subsequent GUI elements: tooltips, scoreboard, toasts drawn after this item's render
//$$  * naturally cover the outline because they paint over mainTarget at later draw calls.
//$$  */
//$$ public final class GuiImmediateGlowComposite {
//$$
//$$     // 4 vertices × 24 bytes (POSITION_TEX_COLOR) = 96 bytes plus alignment slack —
//$$     // 1 KiB is generous and amortizes the malloc across all GUI glow draws this run.
//$$     // Allocated lazily on first use; freed by disposeQuadBuf() on resource reload.
//$$     private static ByteBufferBuilder QUAD_BUF;
//$$
//$$     static {
//$$         GlowResources.register(GuiImmediateGlowComposite::disposeQuadBuf);
//$$     }
//$$
//$$     private GuiImmediateGlowComposite() {}
//$$
//$$     private static ByteBufferBuilder quadBuf() {
//$$         if (QUAD_BUF == null) {
//$$             QUAD_BUF = new ByteBufferBuilder(1024);
//$$         }
//$$         return QUAD_BUF;
//$$     }
//$$
//$$     private static void disposeQuadBuf() {
//$$         if (QUAD_BUF != null) {
//$$             QUAD_BUF.close();
//$$             QUAD_BUF = null;
//$$         }
//$$     }
//$$
//$$     /**
//$$      * Compose one item's glow. Caller must pass ABSOLUTE GUI-px coordinates of the slot's
//$$      * top-left — vanilla renderItem args are slot-local under any outer pose.translate
//$$      * (e.g. AbstractContainerScreen translates by leftPos/topPos before slot rendering),
//$$      * so derive screen position from {@code pose.last()}'s translation column.
//$$      */
//$$     public static void composeForItem(ItemEffectConfig cfg,
//$$                                        CaptureSites.DelayingMultiBufferSource buf,
//$$                                        int itemX, int itemY) {
//$$         if (cfg == null || buf == null) return;
//$$         Minecraft mc = Minecraft.getInstance();
//$$         RenderTarget mainTarget = mc.getMainRenderTarget();
//$$         if (mainTarget == null || mainTarget.getColorTexture() == null) return;
//$$
//$$         int guiScale = (int) Math.max(1, Math.round(mc.getWindow().getGuiScale()));
//$$         TextureTarget tile = GuiImmediateGlowTile.ensureTile(guiScale);
//$$         if (tile == null) return;
//$$
//$$         // 1. Render mesh into tile under custom ortho.
//$$         GuiImmediateGlowTile.renderMeshToTile(buf, itemX, itemY);
//$$
//$$         // 2. Draw glow quad onto mainTarget, sampling the tile.
//$$         drawGlowQuad(cfg, tile, itemX, itemY, mainTarget, mc);
//$$     }
//$$
//$$     /** Additive overlay quad on mainTarget. UBO block rewritten by ShaderUboCompatMixin. */
//$$     private static void drawGlowQuad(ItemEffectConfig cfg, TextureTarget tile,
//$$                                       int itemX, int itemY, RenderTarget mainTarget,
//$$                                       Minecraft mc) {
//$$         RenderPipeline pipe = GuiImmediateGlowPipeline.getOrCreate(cfg);
//$$         if (pipe == null) return;
//$$
//$$         int margin = GuiImmediateGlowTile.MASK_QUAD_MARGIN_GUI_PX;
//$$         int slot = GuiImmediateGlowTile.ITEM_SLOT_GUI_PX;
//$$         int x0 = itemX - margin;
//$$         int x1 = itemX + slot + margin;
//$$         int y0 = itemY - margin;
//$$         int y1 = itemY + slot + margin;
//$$         // z=0 worldspace + GUI MV translation(0,0,-11000) → vz_after_MV = -11000 → in
//$$         // GUI ortho clip volume (zNear=1000, zFar=21000 → vz_clip ∈ [-21000, -1000]).
//$$         // Depth test is disabled on the pipeline anyway, but the value still needs to
//$$         // pass the CLIP volume so the fragment is rasterized.
//$$         float z = 0.0f;
//$$
//$$         // QUADS top-left → bottom-left winding for POSITION_TEX_COLOR. UVs play back the
//$$         // tile upright on screen — tile entry ortho rendered with bottom-left at uv (0,0).
//$$         BufferBuilder bb = new BufferBuilder(quadBuf(), VertexFormat.Mode.QUADS,
//$$                 DefaultVertexFormat.POSITION_TEX_COLOR);
//$$         bb.addVertex(x0, y0, z).setUv(0f, 1f).setColor(0xFFFFFFFF);
//$$         bb.addVertex(x0, y1, z).setUv(0f, 0f).setColor(0xFFFFFFFF);
//$$         bb.addVertex(x1, y1, z).setUv(1f, 0f).setColor(0xFFFFFFFF);
//$$         bb.addVertex(x1, y0, z).setUv(1f, 1f).setColor(0xFFFFFFFF);
//$$
//$$         try (var mesh = bb.build()) {
//$$             if (mesh == null) return;
//$$             var vb = mesh.vertexBuffer();
//$$             var ds = mesh.drawState();
//$$             if (vb == null || ds == null) return;
//$$             var fmt = ds.format();
//$$
//$$             var vBuf = fmt.uploadImmediateVertexBuffer(vb);
//$$             if (vBuf == null) return;
//$$
//$$             // QUADS mode emits no index buffer; use the vanilla sequential buffer
//$$             // (0,1,2,2,3,0 pattern) sized for our 6 indices.
//$$             com.mojang.blaze3d.buffers.GpuBuffer iBuf;
//$$             VertexFormat.IndexType iType;
//$$             var ib = mesh.indexBuffer();
//$$             if (ib == null) {
//$$                 var seq = RenderSystem.getSequentialBuffer(ds.mode());
//$$                 iBuf = seq.getBuffer(ds.indexCount());
//$$                 iType = seq.type();
//$$             } else {
//$$                 iBuf = fmt.uploadImmediateIndexBuffer(ib);
//$$                 iType = ds.indexType();
//$$             }
//$$             if (iBuf == null) return;
//$$
//$$             // Configure tile sampler state. LINEAR filter softens the discrete texel
//$$             // edges that a 16-px (× guiScale) item tile produces — ring-sampling under
//$$             // bilinear filtering aliases less than NEAREST. CLAMP_TO_EDGE on the
//$$             // 4-px guard band reads alpha=0 outside the slot, fading the outline.
//$$             var tileColor = tile.getColorTexture();
//$$             tileColor.setTextureFilter(FilterMode.LINEAR, false);
//$$             tileColor.setAddressMode(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE);
//$$
//$$             var encoder = RenderSystem.getDevice().createCommandEncoder();
//$$             try (RenderPass pass = encoder.createRenderPass(mainTarget.getColorTexture(),
//$$                     OptionalInt.empty())) {
//$$                 pass.setPipeline(pipe);
//$$                 pass.bindSampler("Sampler0", tileColor);
//$$                 // Base GUI uniform — pipeline inherits ColorModulator from
//$$                 // GUI_TEXTURED_SNIPPET; default identity (1,1,1,1) preserves vertex
//$$                 // color. Without this set, the driver leaves it at zero and the
//$$                 // shader output multiplies to black.
//$$                 pass.setUniform("ColorModulator", 1f, 1f, 1f, 1f);
//$$                 // Glow uniforms (matching ShaderUboCompatMixin's per-member rewrite of
//$$                 // the GalliumGuiGlow UBO block in core/<shader>_gui.fsh).
//$$                 pass.setUniform("FrameTimeCounter", GlowTime.guiSecondsFloat());
//$$                 var window = mc.getWindow();
//$$                 pass.setUniform("ScreenSize",
//$$                         (float) window.getWidth(), (float) window.getHeight());
//$$                 pass.setUniform("ShaderAlign", 1f, 1f, 0f, 0f);
//$$                 // Per-config user params; must match pipeline's individual uniform decls.
//$$                 for (ShaderParam p : cfg.params()) {
//$$                     switch (p) {
//$$                         case ShaderParam.Float f -> pass.setUniform(f.name(), f.value());
//$$                         case ShaderParam.Vec2 v -> pass.setUniform(v.name(), v.x(), v.y());
//$$                         case ShaderParam.Vec3 v -> pass.setUniform(v.name(), v.x(), v.y(), v.z());
//$$                         case ShaderParam.Vec4 v -> pass.setUniform(v.name(), v.x(), v.y(), v.z(), v.w());
//$$                     }
//$$                 }
//$$
//$$                 pass.setVertexBuffer(0, vBuf);
//$$                 pass.setIndexBuffer(iBuf, iType);
//$$                 pass.drawIndexed(0, ds.indexCount());
//$$             }
//$$         }
//$$     }
//$$ }
//#else
public final class GuiImmediateGlowComposite {
    private GuiImmediateGlowComposite() {}
}
//#endif
//#else
//$$ import cn.spectra.gallium.glowoutline.ItemEffectConfig;
//$$ import cn.spectra.gallium.glowoutline.ShaderParam;
//$$ import cn.spectra.gallium.glowoutline.capture.CaptureSites;
//$$ import com.mojang.blaze3d.pipeline.RenderTarget;
//$$ import com.mojang.blaze3d.pipeline.TextureTarget;
//$$ import com.mojang.blaze3d.systems.RenderSystem;
//$$ import com.mojang.blaze3d.vertex.BufferBuilder;
//$$ import com.mojang.blaze3d.vertex.BufferUploader;
//$$ import com.mojang.blaze3d.vertex.ByteBufferBuilder;
//$$ import com.mojang.blaze3d.vertex.DefaultVertexFormat;
//$$ import com.mojang.blaze3d.vertex.VertexFormat;
//$$ import net.minecraft.client.Minecraft;
//#if MC>=1_21_02
//$$ import net.minecraft.client.renderer.CompiledShaderProgram;
//#else
//$$ import net.minecraft.client.renderer.ShaderInstance;
//#endif
//$$
//$$ public final class GuiImmediateGlowComposite {
//$$     // Reused native buffer; held statically to avoid per-frame malloc. Registered with
//$$     // GlowResources so the native allocation is released on resource reload (re-allocated
//$$     // lazily on next use after dispose).
//$$     private static ByteBufferBuilder QUAD_BUF;
//$$
//$$     static {
//$$         GlowResources.register(GuiImmediateGlowComposite::disposeQuadBuf);
//$$     }
//$$
//$$     private GuiImmediateGlowComposite() {}
//$$
//$$     private static ByteBufferBuilder quadBuf() {
//$$         if (QUAD_BUF == null) {
//$$             // 4 vertices × 24B (POSITION_TEX_COLOR) ≈ 96B; 1 KiB amortizes alignment slack.
//$$             QUAD_BUF = new ByteBufferBuilder(1024);
//$$         }
//$$         return QUAD_BUF;
//$$     }
//$$
//$$     private static void disposeQuadBuf() {
//$$         if (QUAD_BUF != null) {
//$$             QUAD_BUF.close();
//$$             QUAD_BUF = null;
//$$         }
//$$     }
//$$
//$$     public static void composeForItem(ItemEffectConfig cfg,
//$$                                        CaptureSites.DelayingMultiBufferSource buf,
//$$                                        int itemX, int itemY) {
//$$         if (cfg == null || buf == null) return;
//$$         Minecraft mc = Minecraft.getInstance();
//$$         RenderTarget mainTarget = mc.getMainRenderTarget();
//$$         if (mainTarget == null || mainTarget.getColorTextureId() == -1) return;
//$$         int guiScale = (int) Math.max(1, Math.round(mc.getWindow().getGuiScale()));
//$$         TextureTarget tile = GuiImmediateGlowTile.ensureTile(guiScale);
//$$         if (tile == null) return;
//$$         try {
//$$             GuiImmediateGlowTile.renderMeshToTile(buf, itemX, itemY);
//$$             drawGlowQuad(cfg, tile, itemX, itemY, mainTarget, mc);
//$$         } finally {
//$$             // renderMeshToTile binds the tile target then unbinds (FB=0). If drawGlowQuad
//$$             // returns early (e.g. program failed to compile), no path re-binds mainTarget,
//$$             // and the next GUI element rendered after this item lands on the default
//$$             // framebuffer (window backbuffer) instead of mainTarget — invisible until
//$$             // the next vanilla bindWrite. Restore here so every composeForItem call is
//$$             // a no-op on the surrounding GUI framebuffer state.
//$$             mainTarget.bindWrite(true);
//$$         }
//$$     }
//$$
//$$     private static void drawGlowQuad(ItemEffectConfig cfg, TextureTarget tile,
//$$                                       int itemX, int itemY, RenderTarget mainTarget,
//$$                                       Minecraft mc) {
//#if MC>=1_21_02
//$$         CompiledShaderProgram program = GuiImmediateGlowPipeline.getOrCreate(cfg);
//#else
//$$         ShaderInstance program = GuiImmediateGlowPipeline.getOrCreate(cfg);
//#endif
//$$         if (program == null) return;
//$$         int margin = GuiImmediateGlowTile.MASK_QUAD_MARGIN_GUI_PX;
//$$         int slot = GuiImmediateGlowTile.ITEM_SLOT_GUI_PX;
//$$         int x0 = itemX - margin;
//$$         int x1 = itemX + slot + margin;
//$$         int y0 = itemY - margin;
//$$         int y1 = itemY + slot + margin;
//$$
//$$         // BufferUploader.drawWithShader → VertexBuffer.drawWithShader → setDefaultUniforms
//$$         // unconditionally calls bindSampler("Sampler" + i, RenderSystem.getShaderTexture(i))
//$$         // for i=0..11, OVERWRITING any prior explicit Sampler0 binding. Setting the tile
//$$         // via RenderSystem.setShaderTexture(0, ...) means setDefaultUniforms re-binds
//$$         // Sampler0 to the tile (not whatever was last in slot 0). The named-sampler bindings
//$$         // we use for the world shader (DiffuseSampler / MaskSampler / ...) escape this because
//$$         // setDefaultUniforms only resets the Sampler0..11 slots, not arbitrary names.
//$$         RenderSystem.setShaderTexture(0, tile.getColorTextureId());
//$$         program.safeGetUniform("ColorModulator").set(1f, 1f, 1f, 1f);
//$$         program.safeGetUniform("FrameTimeCounter").set(GlowTime.guiSecondsFloat());
//$$         program.safeGetUniform("ScreenSize").set((float) mc.getWindow().getWidth(), (float) mc.getWindow().getHeight());
//$$         program.safeGetUniform("ShaderAlign").set(1f, 1f, 0f, 0f);
//$$         for (ShaderParam p : cfg.params()) {
//$$             switch (p) {
//$$                 case ShaderParam.Float f -> program.safeGetUniform(f.name()).set(f.value());
//$$                 case ShaderParam.Vec2 v -> program.safeGetUniform(v.name()).set(v.x(), v.y());
//$$                 case ShaderParam.Vec3 v -> program.safeGetUniform(v.name()).set(v.x(), v.y(), v.z());
//$$                 case ShaderParam.Vec4 v -> program.safeGetUniform(v.name()).set(v.x(), v.y(), v.z(), v.w());
//$$             }
//$$         }
//$$
//$$         RenderSystem.bindTexture(tile.getColorTextureId());
//$$         com.mojang.blaze3d.platform.GlStateManager._texParameter(3553, 10241, 9729);
//$$         com.mojang.blaze3d.platform.GlStateManager._texParameter(3553, 10240, 9729);
//$$         com.mojang.blaze3d.platform.GlStateManager._texParameter(3553, 10242, 33071);
//$$         com.mojang.blaze3d.platform.GlStateManager._texParameter(3553, 10243, 33071);
//$$
//$$         mainTarget.bindWrite(true);
//$$         RenderSystem.disableDepthTest();
//$$         RenderSystem.depthMask(false);
//$$         RenderSystem.disableCull();
//$$         RenderSystem.enableBlend();
//$$         RenderSystem.blendFuncSeparate(1, 1, 1, 0);
//#if MC>=1_21_02
//$$         RenderSystem.setShader(program);
//#else
//$$         final ShaderInstance shaderRef = program;
//$$         RenderSystem.setShader(() -> shaderRef);
//#endif
//$$         try (var mesh = buildQuad(x0, y0, x1, y1)) {
//$$             BufferUploader.drawWithShader(mesh);
//$$         } finally {
//$$             RenderSystem.defaultBlendFunc();
//$$             RenderSystem.disableBlend();
//$$             RenderSystem.enableCull();
//$$             RenderSystem.depthMask(true);
//$$             RenderSystem.enableDepthTest();
//$$         }
//$$     }
//$$
//$$     private static com.mojang.blaze3d.vertex.MeshData buildQuad(int x0, int y0, int x1, int y1) {
//$$         BufferBuilder bb = new BufferBuilder(quadBuf(), VertexFormat.Mode.QUADS,
//$$                 DefaultVertexFormat.POSITION_TEX_COLOR);
//$$         bb.addVertex(x0, y0, 0.0f).setUv(0f, 1f).setColor(0xFFFFFFFF);
//$$         bb.addVertex(x0, y1, 0.0f).setUv(0f, 0f).setColor(0xFFFFFFFF);
//$$         bb.addVertex(x1, y1, 0.0f).setUv(1f, 0f).setColor(0xFFFFFFFF);
//$$         bb.addVertex(x1, y0, 0.0f).setUv(1f, 1f).setColor(0xFFFFFFFF);
//$$         return bb.buildOrThrow();
//$$     }
//$$ }
//#endif
