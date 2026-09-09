package cn.spectra.gallium.glowoutline.capture;

//#if MC>=1_21_09
import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
//#if MC>=1_26_00
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
//#else
//$$ import net.minecraft.client.renderer.block.model.BlockStateModel;
//#endif
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
//#if MC>=1_26_00
import net.minecraft.client.renderer.state.level.CameraRenderState;
//#else
//$$ import net.minecraft.client.renderer.state.CameraRenderState;
//#endif
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
//#if MC>=1_26_00
import net.minecraft.client.resources.model.geometry.BakedQuad;
//#else
//$$ import net.minecraft.client.renderer.block.model.BakedQuad;
//$$ import net.minecraft.world.level.block.state.BlockState;
//#endif
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Mirror submit calls into the active glow capture so the offscreen pass renders the same nodes.
 * <p>
 * The outer storage's direct {@code submitModel/submitItem/...} entry points (called when callers
 * skip the {@code order(int)} path) forward to the delegate first and then mirror directly into
 * capture order 0: vanilla's default ordering sits at order 0, so this preserves draw
 * layering for the rare callers that use the storage directly. The inner
 * {@link DuplicatingSubmitNodeCollection} mirrors at whatever order value was requested via
 * {@link #order(int)}.
 */
public final class DuplicatingSubmitNodeStorage extends SubmitNodeStorage {

    /** Bounds heavy SubmitNodeCollection phase graphs when a mod emits ever-changing orders. */
    private static final int MAX_CACHED_ORDERS = 64;

    /** Reused as the no-op super-storage for inner collections. The collection delegates everything
     *  to its real {@code delegate}; the super(SubmitNodeStorage) call is just to satisfy the
     *  superclass constructor and never receives any submits. */
    private static final SubmitNodeStorage SENTINEL = new SubmitNodeStorage();

    private @Nullable SubmitNodeStorage delegate;
    private @Nullable GlowCaptureState state;
    private final Int2ObjectOpenHashMap<DuplicatingSubmitNodeCollection> collectionsByOrder = new Int2ObjectOpenHashMap<>();

    public DuplicatingSubmitNodeStorage(SubmitNodeStorage delegate, GlowCaptureState state) {
        reset(delegate, state);
    }

    /** Rebinds this pooled wrapper and releases every reference to the previous delegate. */
    public void reset(SubmitNodeStorage delegate, GlowCaptureState state) {
        if (delegate == null || state == null) throw new IllegalArgumentException("delegate/state");
        this.delegate = delegate;
        this.state = state;
    }

    /**
     * Stops Gallium mirroring without invalidating the collector that vanilla is still using.
     * <p>
     * Capture invalidation can happen in the middle of one renderer invocation (for example when
     * a streaming domain closes between two submits).  Clearing {@link #delegate} there would make
     * the renderer's next vanilla submit fail.  The scope owner calls {@link #detach()} only after
     * the wrapped renderer returns.
     */
    public void disableCapture() {
        this.state = null;
    }

    /** Full scope-end detach, called only after the wrapped vanilla submission returns. */
    public void detach() {
        this.delegate = null;
        this.state = null;
    }

    private SubmitNodeStorage delegate() {
        SubmitNodeStorage current = delegate;
        if (current == null) throw new IllegalStateException("Detached capture submit wrapper");
        return current;
    }

    private @Nullable OrderedSubmitNodeCollector captureCollector(int order) {
        GlowCaptureState current = state;
        SubmitNodeStorage capture = GlowCaptureManager.captureStorageFor(current);
        if (capture == null) return null;
        GlowCaptureManager.markCaptureStorageDirty(current);
        return capture.order(order);
    }

    private void captured() {
        GlowCaptureState current = state;
        if (current != null) GlowCaptureManager.markCaptured(current);
    }

    @Override
    public SubmitNodeCollection order(int order) {
        DuplicatingSubmitNodeCollection cached = collectionsByOrder.get(order);
        if (cached != null) return cached;
        DuplicatingSubmitNodeCollection created = new DuplicatingSubmitNodeCollection(order);
        if (collectionsByOrder.size() >= MAX_CACHED_ORDERS) return created;
        collectionsByOrder.put(order, created);
        return created;
    }

    @Override public void submitShadow(PoseStack p, float r, List<EntityRenderState.ShadowPiece> pieces) { delegate().submitShadow(p, r, pieces); }
    //#if MC>=1_26_02
    //$$ @Override public void submitNameTag(PoseStack p, @Nullable Vec3 a, int o, Component n, boolean s, int l, CameraRenderState c) { delegate().submitNameTag(p, a, o, n, s, l, c); }
    //#else
    @Override public void submitNameTag(PoseStack p, @Nullable Vec3 a, int o, Component n, boolean s, int l, double d, CameraRenderState c) { delegate().submitNameTag(p, a, o, n, s, l, d, c); }
    //#endif
    @Override public void submitText(PoseStack p, float x, float y, FormattedCharSequence str, boolean ds, Font.DisplayMode dm, int l, int col, int bg, int oc) { delegate().submitText(p, x, y, str, ds, dm, l, col, bg, oc); }
    @Override public void submitFlame(PoseStack p, EntityRenderState rs, Quaternionf q) { delegate().submitFlame(p, rs, q); }
    @Override public void submitLeash(PoseStack p, EntityRenderState.LeashState ls) { delegate().submitLeash(p, ls); }
    //#if MC>=1_26_02
    //$$ @Override public void submitMovingBlock(PoseStack p, MovingBlockRenderState mb, int oc) { delegate().submitMovingBlock(p, mb, oc); }
    //#else
    @Override public void submitMovingBlock(PoseStack p, MovingBlockRenderState mb) { delegate().submitMovingBlock(p, mb); }
    //#endif
    //#if MC>=1_26_02
    //$$ @Override public void submitBreakingBlockModel(PoseStack p, List<BlockStateModelPart> parts, int prog) { delegate().submitBreakingBlockModel(p, parts, prog); }
    //#elseif MC>=1_26_00
    @Override public void submitBreakingBlockModel(PoseStack p, BlockStateModel m, long seed, int prog) { delegate().submitBreakingBlockModel(p, m, seed, prog); }
    //#else
    //$$ @Override public void submitBlock(PoseStack p, BlockState bs, int i, int j, int k) { delegate().submitBlock(p, bs, i, j, k); }
    //#endif
    //#if MC<1_26_02
    @Override public void submitParticleGroup(ParticleGroupRenderer r) { delegate().submitParticleGroup(r); }
    @Override public void clear() { delegate().clear(); }
    @Override public void endFrame() { delegate().endFrame(); }
    //#endif
    @Override public Int2ObjectAVLTreeMap<SubmitNodeCollection> getSubmitsPerOrder() { return delegate().getSubmitsPerOrder(); }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack p, RenderType rt, int l, int ov, int tc, @Nullable TextureAtlasSprite sp, int oc, ModelFeatureRenderer.@Nullable CrumblingOverlay cr) {
        delegate().submitModel(model, state, p, rt, l, ov, tc, sp, oc, cr);
        OrderedSubmitNodeCollector capture = captureCollector(0);
        if (capture != null) {
            capture.submitModel(model, state, p, rt, l, ov, tc, sp, oc, cr);
            captured();
        }
    }

    //#if MC<1_26_02
    @Override
    public void submitModelPart(ModelPart mp, PoseStack p, RenderType rt, int l, int ov, @Nullable TextureAtlasSprite sp, boolean sh, boolean hf, int tc, ModelFeatureRenderer.@Nullable CrumblingOverlay cr, int oc) {
        delegate().submitModelPart(mp, p, rt, l, ov, sp, sh, hf, tc, cr, oc);
        OrderedSubmitNodeCollector capture = captureCollector(0);
        if (capture != null) {
            capture.submitModelPart(mp, p, rt, l, ov, sp, sh, hf, tc, cr, oc);
            captured();
        }
    }
    //#endif

    //#if MC>=1_26_00
    @Override
    public void submitBlockModel(PoseStack p, RenderType rt, List<BlockStateModelPart> parts, int[] tints, int l, int ov, int oc) {
        delegate().submitBlockModel(p, rt, parts, tints, l, ov, oc);
        OrderedSubmitNodeCollector capture = captureCollector(0);
        if (capture != null) {
            capture.submitBlockModel(p, rt, parts, tints, l, ov, oc);
            captured();
        }
    }

    @Override
    public void submitItem(PoseStack p, ItemDisplayContext dc, int l, int ov, int oc, int[] tints, List<BakedQuad> quads, ItemStackRenderState.FoilType ft) {
        delegate().submitItem(p, dc, l, ov, oc, tints, quads, ft);
        OrderedSubmitNodeCollector capture = captureCollector(0);
        if (capture != null) {
            capture.submitItem(p, dc, l, ov, oc, tints, quads, ft);
            captured();
        }
    }
    //#else
    //$$ @Override
    //$$ public void submitBlockModel(PoseStack p, RenderType rt, BlockStateModel m, float fr, float fg, float fb, int l, int ov, int oc) {
    //$$     delegate().submitBlockModel(p, rt, m, fr, fg, fb, l, ov, oc);
    //$$     OrderedSubmitNodeCollector capture = captureCollector(0);
    //$$     if (capture != null) {
    //$$         capture.submitBlockModel(p, rt, m, fr, fg, fb, l, ov, oc);
    //$$         captured();
    //$$     }
    //$$ }
    //$$
    //$$ @Override
    //$$ public void submitItem(PoseStack p, ItemDisplayContext dc, int l, int ov, int oc, int[] tints, List<BakedQuad> quads, RenderType rt, ItemStackRenderState.FoilType ft) {
    //$$     delegate().submitItem(p, dc, l, ov, oc, tints, quads, rt, ft);
    //$$     OrderedSubmitNodeCollector capture = captureCollector(0);
    //$$     if (capture != null) {
    //$$         capture.submitItem(p, dc, l, ov, oc, tints, quads, rt, ft);
    //$$         captured();
    //$$     }
    //$$ }
    //#endif

    @Override
    public void submitCustomGeometry(PoseStack p, RenderType rt, CustomGeometryRenderer cgr) {
        delegate().submitCustomGeometry(p, rt, cgr);
        OrderedSubmitNodeCollector capture = captureCollector(0);
        if (capture != null) {
            capture.submitCustomGeometry(p, rt, cgr);
            captured();
        }
    }

    private final class DuplicatingSubmitNodeCollection extends SubmitNodeCollection {
        private final int order;

        DuplicatingSubmitNodeCollection(int order) {
            //#if MC>=1_26_02
            //$$ // 26.2: SubmitNodeCollection no longer takes a SubmitNodeStorage — implicit no-arg ctor.
            //$$ super();
            //#else
            super(SENTINEL);
            //#endif
            this.order = order;
        }

        private SubmitNodeCollection delegate() {
            return DuplicatingSubmitNodeStorage.this.delegate().order(order);
        }

        @Override public void submitShadow(PoseStack p, float r, List<EntityRenderState.ShadowPiece> pieces) { delegate().submitShadow(p, r, pieces); }
        //#if MC>=1_26_02
        //$$ @Override public void submitNameTag(PoseStack p, @Nullable Vec3 a, int o, Component n, boolean s, int l, CameraRenderState c) { delegate().submitNameTag(p, a, o, n, s, l, c); }
        //#else
        @Override public void submitNameTag(PoseStack p, @Nullable Vec3 a, int o, Component n, boolean s, int l, double d, CameraRenderState c) { delegate().submitNameTag(p, a, o, n, s, l, d, c); }
        //#endif
        @Override public void submitText(PoseStack p, float x, float y, FormattedCharSequence str, boolean ds, Font.DisplayMode dm, int l, int col, int bg, int oc) { delegate().submitText(p, x, y, str, ds, dm, l, col, bg, oc); }
        @Override public void submitFlame(PoseStack p, EntityRenderState rs, Quaternionf q) { delegate().submitFlame(p, rs, q); }
        @Override public void submitLeash(PoseStack p, EntityRenderState.LeashState ls) { delegate().submitLeash(p, ls); }
        //#if MC>=1_26_02
        //$$ @Override public void submitMovingBlock(PoseStack p, MovingBlockRenderState mb, int oc) { delegate().submitMovingBlock(p, mb, oc); }
        //#else
        @Override public void submitMovingBlock(PoseStack p, MovingBlockRenderState mb) { delegate().submitMovingBlock(p, mb); }
        //#endif
        //#if MC>=1_26_02
        //$$ @Override public void submitBreakingBlockModel(PoseStack p, List<BlockStateModelPart> parts, int prog) { delegate().submitBreakingBlockModel(p, parts, prog); }
        //#elseif MC>=1_26_00
        @Override public void submitBreakingBlockModel(PoseStack p, BlockStateModel m, long seed, int prog) { delegate().submitBreakingBlockModel(p, m, seed, prog); }
        //#else
        //$$ @Override public void submitBlock(PoseStack p, BlockState bs, int i, int j, int k) { delegate().submitBlock(p, bs, i, j, k); }
        //#endif
        //#if MC<1_26_02
        @Override public void submitParticleGroup(ParticleGroupRenderer r) { delegate().submitParticleGroup(r); }
        //#endif

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack p, RenderType rt, int l, int ov, int tc, @Nullable TextureAtlasSprite sp, int oc, ModelFeatureRenderer.@Nullable CrumblingOverlay cr) {
            delegate().submitModel(model, state, p, rt, l, ov, tc, sp, oc, cr);
            OrderedSubmitNodeCollector capture = captureCollector(order);
            if (capture != null) {
                capture.submitModel(model, state, p, rt, l, ov, tc, sp, oc, cr);
                captured();
            }
        }

        //#if MC<1_26_02
        @Override
        public void submitModelPart(ModelPart mp, PoseStack p, RenderType rt, int l, int ov, @Nullable TextureAtlasSprite sp, boolean sh, boolean hf, int tc, ModelFeatureRenderer.@Nullable CrumblingOverlay cr, int oc) {
            delegate().submitModelPart(mp, p, rt, l, ov, sp, sh, hf, tc, cr, oc);
            OrderedSubmitNodeCollector capture = captureCollector(order);
            if (capture != null) {
                capture.submitModelPart(mp, p, rt, l, ov, sp, sh, hf, tc, cr, oc);
                captured();
            }
        }
        //#endif

        //#if MC>=1_26_00
        @Override
        public void submitBlockModel(PoseStack p, RenderType rt, List<BlockStateModelPart> parts, int[] tints, int l, int ov, int oc) {
            delegate().submitBlockModel(p, rt, parts, tints, l, ov, oc);
            OrderedSubmitNodeCollector capture = captureCollector(order);
            if (capture != null) {
                capture.submitBlockModel(p, rt, parts, tints, l, ov, oc);
                captured();
            }
        }

        @Override
        public void submitItem(PoseStack p, ItemDisplayContext dc, int l, int ov, int oc, int[] tints, List<BakedQuad> quads, ItemStackRenderState.FoilType ft) {
            delegate().submitItem(p, dc, l, ov, oc, tints, quads, ft);
            OrderedSubmitNodeCollector capture = captureCollector(order);
            if (capture != null) {
                capture.submitItem(p, dc, l, ov, oc, tints, quads, ft);
                captured();
            }
        }
        //#else
        //$$ @Override
        //$$ public void submitBlockModel(PoseStack p, RenderType rt, BlockStateModel m, float fr, float fg, float fb, int l, int ov, int oc) {
        //$$     delegate().submitBlockModel(p, rt, m, fr, fg, fb, l, ov, oc);
        //$$     OrderedSubmitNodeCollector capture = captureCollector(order);
        //$$     if (capture != null) {
        //$$         capture.submitBlockModel(p, rt, m, fr, fg, fb, l, ov, oc);
        //$$         captured();
        //$$     }
        //$$ }
        //$$
        //$$ @Override
        //$$ public void submitItem(PoseStack p, ItemDisplayContext dc, int l, int ov, int oc, int[] tints, List<BakedQuad> quads, RenderType rt, ItemStackRenderState.FoilType ft) {
        //$$     delegate().submitItem(p, dc, l, ov, oc, tints, quads, rt, ft);
        //$$     OrderedSubmitNodeCollector capture = captureCollector(order);
        //$$     if (capture != null) {
        //$$         capture.submitItem(p, dc, l, ov, oc, tints, quads, rt, ft);
        //$$         captured();
        //$$     }
        //$$ }
        //#endif

        @Override
        public void submitCustomGeometry(PoseStack p, RenderType rt, CustomGeometryRenderer cgr) {
            delegate().submitCustomGeometry(p, rt, cgr);
            OrderedSubmitNodeCollector capture = captureCollector(order);
            if (capture != null) {
                capture.submitCustomGeometry(p, rt, cgr);
                captured();
            }
        }
    }
}
//#else
//$$ // Mirrors submits into the 1.21.9 SubmitNodeStorage; that class is absent on
//$$ // 1.21.6–1.21.8. The pre-1.21.9 world path uses CaptureSites.DelayingMultiBufferSource
//$$ // instead. No callers here (CaptureSites is stubbed), so this is an empty placeholder.
//$$ public final class DuplicatingSubmitNodeStorage {
//$$     private DuplicatingSubmitNodeStorage() {}
//$$ }
//#endif
