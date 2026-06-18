package cn.spectra.gallium.glowoutline.shader;

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
//$$     // Reused per call: bb.build() returns a MeshData whose close() rewinds this buffer.
//$$     private static final ByteBufferBuilder QUAD_BUF = new ByteBufferBuilder(1024);
//$$
//$$     private GuiImmediateGlowComposite() {}
//$$
//$$     /**
//$$      * Compose one item's glow. Fills the shared tile from {@code buf} and overlays a
//$$      * glow quad on {@code mainTarget} at the item's GUI screen position.
//$$      *
//$$      * <p>Caller (GuiGraphicsItemMixin) is responsible for:
//$$      * <ul>
//$$      *   <li>Filtering on stack / config / feature toggle BEFORE building {@code buf}.</li>
//$$      *   <li>Pooling {@code buf}'s lifetime (typically allocated lazily and reused
//$$      *       across calls).</li>
//$$      *   <li>Ensuring {@code buf} contains the item's mesh in vertex GUI-screen-space
//$$      *       — i.e., the tee was active during {@code ItemStackRenderState.render(pose, ...)}
//$$      *       with the same pose vanilla applied.</li>
//$$      *   <li>Resolving {@code itemX} / {@code itemY} as ABSOLUTE GUI-px coordinates of the
//$$      *       slot's top-left corner. The renderItem args are slot-local under any outer
//$$      *       {@code pose.translate(...)} applied by the screen (e.g. AbstractContainerScreen
//$$      *       translates by {@code (leftPos, topPos, 0)} before slot rendering); pass the
//$$      *       absolute screen position derived from {@code pose.last()} translation column.</li>
//$$      * </ul>
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
//$$     /**
//$$      * Emits the additive overlay quad (24 GUI-px on each side, centred on the item's
//$$      * 16-px slot with 4-px padding) on mainTarget. Sampler0 = tile, fragment shader =
//$$      * {@code core/<shader>_gui.fsh} (same shader 1.21.6+ uses; the
//$$      * {@code GalliumGuiGlow} UBO block is rewritten to individual uniforms by
//$$      * {@link cn.spectra.gallium.glowoutline.mixin.ShaderUboCompatMixin}).
//$$      */
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
//$$         // Build a single quad (4 vertices in QUADS mode → indexed via vanilla's
//$$         // sequential 0,1,2,2,3,0 buffer). Standard top-left/top-right/bottom-right/
//$$         // bottom-left winding to match POSITION_TEX_COLOR's QUADS expectations.
//$$         // UVs: top-left of quad in GUI = top-left of texture (uv 0,1 in OpenGL); the
//$$         // tile was rendered with its bottom-left at uv 0,0 corresponding to GUI bottom
//$$         // (yflipped via the entry ortho), so this UV mapping plays back the alpha
//$$         // shape upright on screen.
//$$         BufferBuilder bb = new BufferBuilder(QUAD_BUF, VertexFormat.Mode.QUADS,
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
//$$                 // Per-config user params (Intensity, PulseSpeed, WaveSpeed, InnerColor,
//$$                 // OuterColor, ...). Match the pipeline's individual uniform declarations.
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
