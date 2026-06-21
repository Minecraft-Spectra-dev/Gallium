package cn.spectra.gallium.glowoutline.shader;

//#if MC>=1_21_05
//#if MC<1_21_06
//$$ import cn.spectra.gallium.glowoutline.IrisCompat;
//$$ import cn.spectra.gallium.glowoutline.capture.CaptureSites;
//$$ import com.mojang.blaze3d.ProjectionType;
//$$ import com.mojang.blaze3d.pipeline.TextureTarget;
//$$ import com.mojang.blaze3d.systems.RenderSystem;
//$$ import org.jspecify.annotations.Nullable;
//$$ import org.joml.Matrix4f;
//$$
//$$ /**
//$$  * Pre-1.21.6 (immediate-mode GUI rendering) only: small off-screen FBO that holds a
//$$  * single GUI item's alpha tile.
//$$  *
//$$  * <p>The mirror of 1.21.6+'s vanilla item atlas tile: each glowing GUI item gets its
//$$  * captured mesh re-rasterized into this tile under a custom orthographic projection
//$$  * that maps the item's GUI screen rect (16x16 GUI-px + 4-px margin on every side) to
//$$  * the tile's full extent. The result is a 2D alpha image that
//$$  * {@link GuiImmediateGlowComposite} samples through {@code core/<shader>_gui.fsh}'s
//$$  * ring sampler — the same algorithm 1.21.6+ runs on the vanilla atlas tile, so the
//$$  * visible outline matches in thickness and color.
//$$  *
//$$  * <p>Reused per-item: the tile is cleared and re-rendered each call, so a single
//$$  * shared instance suffices regardless of how many glowing GUI items appear. Sized to
//$$  * {@code 24 * guiScale} fb-px on each side; resizes when guiScale changes (rare).
//$$  */
//$$ public final class GuiImmediateGlowTile {
//$$
//$$     /** Mirror of 1.21.6+'s {@code GuiGlowDispatcher.ITEM_SLOT_SIZE} — must stay in sync. */
//$$     public static final int ITEM_SLOT_GUI_PX = 16;
//$$     /** Mirror of 1.21.6+'s {@code GuiGlowDispatcher.MASK_QUAD_MARGIN} — must stay in sync. */
//$$     public static final int MASK_QUAD_MARGIN_GUI_PX = 4;
//$$     public static final int TILE_GUI_PX = ITEM_SLOT_GUI_PX + 2 * MASK_QUAD_MARGIN_GUI_PX;
//$$
//$$     @Nullable private static TextureTarget tile;
//$$     /** Reused projection scratch so per-item mesh replay doesn't allocate. */
//$$     private static final Matrix4f SCRATCH_PROJ = new Matrix4f();
//$$
//$$     static {
//$$         GlowResources.register(GuiImmediateGlowTile::dispose);
//$$     }
//$$
//$$     private GuiImmediateGlowTile() {}
//$$
//$$     /** Allocates / resizes the tile to {@code 24 * guiScale} fb-px on each side. */
//$$     public static TextureTarget ensureTile(int guiScale) {
//$$         int sizeFb = Math.max(1, TILE_GUI_PX * Math.max(1, guiScale));
//$$         if (tile == null || tile.width != sizeFb || tile.height != sizeFb) {
//$$             if (tile != null) tile.destroyBuffers();
//$$             tile = new TextureTarget("GuiImmediateGlowTile", sizeFb, sizeFb, true);
//$$         }
//$$         return tile;
//$$     }
//$$
//$$     /**
//$$      * Replays the captured mesh into the tile under a custom orthographic projection that
//$$      * maps the GUI vertex range (itemX-margin..itemX+slot+margin) × (itemY-margin..itemY+slot+margin)
//$$      * to the full tile extent. After return, {@code tile.getColorTexture()} holds the item's
//$$      * 2D alpha tile; {@link GuiImmediateGlowComposite} samples it through the GUI glow shader.
//$$      *
//$$      * <p>Caller must ensure {@code tile} is already sized to the current guiScale via
//$$      * {@link #ensureTile(int)}. The current GUI projection is saved and restored around
//$$      * the replay so the surrounding GUI rendering keeps its ortho.
//$$      */
//$$     public static void renderMeshToTile(CaptureSites.DelayingMultiBufferSource buf,
//$$                                          int itemX, int itemY) {
//$$         if (buf == null || tile == null) return;
//$$
//$$         var encoder = RenderSystem.getDevice().createCommandEncoder();
//$$         // Wipe both color (alpha=0 baseline so empty pixels never get sampled as item)
//$$         // and depth (1.0 = far plane, so any mesh fragment passes the LESS depth test
//$$         // that vanilla item RenderTypes set up via setupRenderState).
//$$         encoder.clearColorTexture(tile.getColorTexture(), 0);
//$$         encoder.clearDepthTexture(tile.getDepthTexture(), 1.0);
//$$
//$$         // Custom ortho. Maps GUI worldspace (vx, vy) to NDC such that:
//$$         //   (itemX-4, itemY-4)         → top-left  of tile
//$$         //   (itemX+20, itemY+20)       → bottom-right of tile
//$$         // The 4-px GUI margin around the 16-px item interior is what gives the ring sampler
//$$         // (in core/<shader>_gui.fsh) "off-item" pixels with alpha=0 to fade outline against —
//$$         // exactly the same construction 1.21.6+ uses with its per-cell guard band.
//$$         //
//$$         // bottom > top in setOrtho is the standard GUI-y-flipped convention (matching the
//$$         // vanilla GUI ortho on mainTarget). near/far reproduces vanilla GUI ortho exactly so
//$$         // the captured mesh's z (after RenderSystem.modelView translation(0,0,-11000)) lands
//$$         // inside the clip volume (vz_after_MV ≈ -10850..-10834 → NDC z ≈ -0.015..-0.017).
//$$         int margin = MASK_QUAD_MARGIN_GUI_PX;
//$$         int slot = ITEM_SLOT_GUI_PX;
//$$         Matrix4f proj = SCRATCH_PROJ.identity().setOrtho(
//$$                 itemX - margin, itemX + slot + margin,
//$$                 itemY + slot + margin, itemY - margin,
//$$                 1000.0f, 21000.0f);
//$$
//$$         RenderSystem.backupProjectionMatrix();
//$$         RenderSystem.setProjectionMatrix(proj, ProjectionType.ORTHOGRAPHIC);
//$$
//$$         // Iris bypass: setupRenderState inside flushToTarget can call into Iris's
//$$         // pipeline-extension hook (vertex format promotion etc.). The captured mesh was
//$$         // built under bypass; we replay under bypass too so vertex format and pipeline
//$$         // selection stay vanilla. No-op when Iris is absent.
//$$         var irisSnap = IrisCompat.setBypass(true);
//$$         try {
//$$             buf.flushToTarget(tile);
//$$         } finally {
//$$             IrisCompat.restoreBypass(irisSnap);
//$$             RenderSystem.restoreProjectionMatrix();
//$$         }
//$$     }
//$$
//$$     public static @Nullable TextureTarget getTile() { return tile; }
//$$
//$$     private static void dispose() {
//$$         if (tile != null) {
//$$             tile.destroyBuffers();
//$$             tile = null;
//$$         }
//$$     }
//$$ }
//#else
public final class GuiImmediateGlowTile {
    private GuiImmediateGlowTile() {}
}
//#endif
//#else
//$$ import cn.spectra.gallium.glowoutline.IrisCompat;
//$$ import cn.spectra.gallium.glowoutline.capture.CaptureSites;
//#if MC>=1_21_02
//$$ import com.mojang.blaze3d.ProjectionType;
//#else
//$$ import com.mojang.blaze3d.vertex.VertexSorting;
//#endif
//$$ import com.mojang.blaze3d.pipeline.TextureTarget;
//$$ import com.mojang.blaze3d.systems.RenderSystem;
//$$ import org.joml.Matrix4f;
//$$ import org.jspecify.annotations.Nullable;
//$$
//$$ public final class GuiImmediateGlowTile {
//$$     public static final int ITEM_SLOT_GUI_PX = 16;
//$$     public static final int MASK_QUAD_MARGIN_GUI_PX = 4;
//$$     public static final int TILE_GUI_PX = ITEM_SLOT_GUI_PX + 2 * MASK_QUAD_MARGIN_GUI_PX;
//$$     @Nullable private static TextureTarget tile;
//$$     private static final Matrix4f SCRATCH_PROJ = new Matrix4f();
//$$     static { GlowResources.register(GuiImmediateGlowTile::dispose); }
//$$     private GuiImmediateGlowTile() {}
//$$
//$$     public static TextureTarget ensureTile(int guiScale) {
//$$         int sizeFb = Math.max(1, TILE_GUI_PX * Math.max(1, guiScale));
//$$         if (tile == null || tile.width != sizeFb || tile.height != sizeFb) {
//$$             if (tile != null) tile.destroyBuffers();
//#if MC>=1_21_02
//$$             tile = new TextureTarget(sizeFb, sizeFb, true);
//#else
//$$             tile = new TextureTarget(sizeFb, sizeFb, true, net.minecraft.client.Minecraft.ON_OSX);
//#endif
//$$         }
//$$         return tile;
//$$     }
//$$
//$$     public static void renderMeshToTile(CaptureSites.DelayingMultiBufferSource buf,
//$$                                          int itemX, int itemY) {
//$$         if (buf == null || tile == null) return;
//$$         tile.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
//#if MC>=1_21_02
//$$         tile.clear();
//#else
//$$         tile.clear(net.minecraft.client.Minecraft.ON_OSX);
//#endif
//$$         int margin = MASK_QUAD_MARGIN_GUI_PX;
//$$         int slot = ITEM_SLOT_GUI_PX;
//$$         Matrix4f proj = SCRATCH_PROJ.identity().setOrtho(
//$$                 itemX - margin, itemX + slot + margin,
//$$                 itemY + slot + margin, itemY - margin,
//$$                 1000.0f, 21000.0f);
//$$         RenderSystem.backupProjectionMatrix();
//#if MC>=1_21_02
//$$         RenderSystem.setProjectionMatrix(proj, ProjectionType.ORTHOGRAPHIC);
//#else
//$$         RenderSystem.setProjectionMatrix(proj, VertexSorting.ORTHOGRAPHIC_Z);
//#endif
//$$         var irisSnap = IrisCompat.setBypass(true);
//$$         try {
//$$             buf.flushToTarget(tile);
//$$         } finally {
//$$             IrisCompat.restoreBypass(irisSnap);
//$$             RenderSystem.restoreProjectionMatrix();
//$$         }
//$$     }
//$$
//$$     public static @Nullable TextureTarget getTile() { return tile; }
//$$     private static void dispose() {
//$$         if (tile != null) {
//$$             tile.destroyBuffers();
//$$             tile = null;
//$$         }
//$$     }
//$$ }
//#endif
