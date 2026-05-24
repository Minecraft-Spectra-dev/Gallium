package cn.spectra.gallium.glowoutline.capture;

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
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * Mirror submit calls into the active glow capture so the offscreen pass renders the same nodes.
 * <p>
 * The outer storage's direct {@code submitModel/submitItem/...} entry points (called when callers
 * skip the {@code order(int)} path) deliberately fall through to {@code duplicate(0, ...)}: vanilla's
 * default ordering sits at order 0, so this preserves draw layering for the rare callers that use
 * the storage directly. The inner {@link DuplicatingSubmitNodeCollection} preserves whatever order
 * value was requested via {@link #order(int)}.
 */
public final class DuplicatingSubmitNodeStorage extends SubmitNodeStorage {

    private final SubmitNodeStorage delegate;
    private final Int2ObjectOpenHashMap<DuplicatingSubmitNodeCollection> collectionsByOrder = new Int2ObjectOpenHashMap<>();

    public DuplicatingSubmitNodeStorage(SubmitNodeStorage delegate) {
        this.delegate = delegate;
    }

    @Override
    public SubmitNodeCollection order(int order) {
        DuplicatingSubmitNodeCollection cached = collectionsByOrder.get(order);
        if (cached != null) return cached;
        DuplicatingSubmitNodeCollection created = new DuplicatingSubmitNodeCollection(delegate.order(order), order);
        collectionsByOrder.put(order, created);
        return created;
    }

    @Override public void submitShadow(PoseStack p, float r, List<EntityRenderState.ShadowPiece> pieces) { delegate.submitShadow(p, r, pieces); }
    @Override public void submitNameTag(PoseStack p, @Nullable Vec3 a, int o, Component n, boolean s, int l, double d, CameraRenderState c) { delegate.submitNameTag(p, a, o, n, s, l, d, c); }
    @Override public void submitText(PoseStack p, float x, float y, FormattedCharSequence str, boolean ds, Font.DisplayMode dm, int l, int col, int bg, int oc) { delegate.submitText(p, x, y, str, ds, dm, l, col, bg, oc); }
    @Override public void submitFlame(PoseStack p, EntityRenderState rs, Quaternionf q) { delegate.submitFlame(p, rs, q); }
    @Override public void submitLeash(PoseStack p, EntityRenderState.LeashState ls) { delegate.submitLeash(p, ls); }
    @Override public void submitMovingBlock(PoseStack p, MovingBlockRenderState mb) { delegate.submitMovingBlock(p, mb); }
    @Override public void submitBreakingBlockModel(PoseStack p, BlockStateModel m, long seed, int prog) { delegate.submitBreakingBlockModel(p, m, seed, prog); }
    @Override public void submitParticleGroup(ParticleGroupRenderer r) { delegate.submitParticleGroup(r); }
    @Override public void clear() { delegate.clear(); }
    @Override public void endFrame() { delegate.endFrame(); }
    @Override public Int2ObjectAVLTreeMap<SubmitNodeCollection> getSubmitsPerOrder() { return delegate.getSubmitsPerOrder(); }

    @Override
    public <S> void submitModel(Model<? super S> model, S state, PoseStack p, RenderType rt, int l, int ov, int tc, @Nullable TextureAtlasSprite sp, int oc, ModelFeatureRenderer.@Nullable CrumblingOverlay cr) {
        delegate.submitModel(model, state, p, rt, l, ov, tc, sp, oc, cr);
        duplicate(0, c -> c.submitModel(model, state, p, rt, l, ov, tc, sp, oc, cr));
    }

    @Override
    public void submitModelPart(ModelPart mp, PoseStack p, RenderType rt, int l, int ov, @Nullable TextureAtlasSprite sp, boolean sh, boolean hf, int tc, ModelFeatureRenderer.@Nullable CrumblingOverlay cr, int oc) {
        delegate.submitModelPart(mp, p, rt, l, ov, sp, sh, hf, tc, cr, oc);
        duplicate(0, c -> c.submitModelPart(mp, p, rt, l, ov, sp, sh, hf, tc, cr, oc));
    }

    @Override
    public void submitBlockModel(PoseStack p, RenderType rt, List<BlockStateModelPart> parts, int[] tints, int l, int ov, int oc) {
        delegate.submitBlockModel(p, rt, parts, tints, l, ov, oc);
        duplicate(0, c -> c.submitBlockModel(p, rt, parts, tints, l, ov, oc));
    }

    @Override
    public void submitItem(PoseStack p, ItemDisplayContext dc, int l, int ov, int oc, int[] tints, List<BakedQuad> quads, ItemStackRenderState.FoilType ft) {
        delegate.submitItem(p, dc, l, ov, oc, tints, quads, ft);
        duplicate(0, c -> c.submitItem(p, dc, l, ov, oc, tints, quads, ft));
    }

    @Override
    public void submitCustomGeometry(PoseStack p, RenderType rt, CustomGeometryRenderer cgr) {
        delegate.submitCustomGeometry(p, rt, cgr);
        duplicate(0, c -> c.submitCustomGeometry(p, rt, cgr));
    }

    private void duplicate(int order, Consumer<OrderedSubmitNodeCollector> consumer) {
        SubmitNodeStorage capture = GlowCaptureManager.captureStorageForCurrent();
        if (capture != null) {
            consumer.accept(capture.order(order));
            var current = GlowCaptureManager.currentCapture();
            if (current != null) current.capturedThisFrame = true;
        }
    }

    private final class DuplicatingSubmitNodeCollection extends SubmitNodeCollection {
        private final SubmitNodeCollection delegate;
        private final int order;

        DuplicatingSubmitNodeCollection(SubmitNodeCollection delegate, int order) {
            super(new SubmitNodeStorage());
            this.delegate = delegate;
            this.order = order;
        }

        @Override public void submitShadow(PoseStack p, float r, List<EntityRenderState.ShadowPiece> pieces) { delegate.submitShadow(p, r, pieces); }
        @Override public void submitNameTag(PoseStack p, @Nullable Vec3 a, int o, Component n, boolean s, int l, double d, CameraRenderState c) { delegate.submitNameTag(p, a, o, n, s, l, d, c); }
        @Override public void submitText(PoseStack p, float x, float y, FormattedCharSequence str, boolean ds, Font.DisplayMode dm, int l, int col, int bg, int oc) { delegate.submitText(p, x, y, str, ds, dm, l, col, bg, oc); }
        @Override public void submitFlame(PoseStack p, EntityRenderState rs, Quaternionf q) { delegate.submitFlame(p, rs, q); }
        @Override public void submitLeash(PoseStack p, EntityRenderState.LeashState ls) { delegate.submitLeash(p, ls); }
        @Override public void submitMovingBlock(PoseStack p, MovingBlockRenderState mb) { delegate.submitMovingBlock(p, mb); }
        @Override public void submitBreakingBlockModel(PoseStack p, BlockStateModel m, long seed, int prog) { delegate.submitBreakingBlockModel(p, m, seed, prog); }
        @Override public void submitParticleGroup(ParticleGroupRenderer r) { delegate.submitParticleGroup(r); }

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack p, RenderType rt, int l, int ov, int tc, @Nullable TextureAtlasSprite sp, int oc, ModelFeatureRenderer.@Nullable CrumblingOverlay cr) {
            delegate.submitModel(model, state, p, rt, l, ov, tc, sp, oc, cr);
            dup(c -> c.submitModel(model, state, p, rt, l, ov, tc, sp, oc, cr));
        }

        @Override
        public void submitModelPart(ModelPart mp, PoseStack p, RenderType rt, int l, int ov, @Nullable TextureAtlasSprite sp, boolean sh, boolean hf, int tc, ModelFeatureRenderer.@Nullable CrumblingOverlay cr, int oc) {
            delegate.submitModelPart(mp, p, rt, l, ov, sp, sh, hf, tc, cr, oc);
            dup(c -> c.submitModelPart(mp, p, rt, l, ov, sp, sh, hf, tc, cr, oc));
        }

        @Override
        public void submitBlockModel(PoseStack p, RenderType rt, List<BlockStateModelPart> parts, int[] tints, int l, int ov, int oc) {
            delegate.submitBlockModel(p, rt, parts, tints, l, ov, oc);
            dup(c -> c.submitBlockModel(p, rt, parts, tints, l, ov, oc));
        }

        @Override
        public void submitItem(PoseStack p, ItemDisplayContext dc, int l, int ov, int oc, int[] tints, List<BakedQuad> quads, ItemStackRenderState.FoilType ft) {
            delegate.submitItem(p, dc, l, ov, oc, tints, quads, ft);
            dup(c -> c.submitItem(p, dc, l, ov, oc, tints, quads, ft));
        }

        @Override
        public void submitCustomGeometry(PoseStack p, RenderType rt, CustomGeometryRenderer cgr) {
            delegate.submitCustomGeometry(p, rt, cgr);
            dup(c -> c.submitCustomGeometry(p, rt, cgr));
        }

        private void dup(Consumer<OrderedSubmitNodeCollector> consumer) {
            SubmitNodeStorage capture = GlowCaptureManager.captureStorageForCurrent();
            if (capture != null) {
                consumer.accept(capture.order(order));
                var current = GlowCaptureManager.currentCapture();
                if (current != null) current.capturedThisFrame = true;
            }
        }
    }
}
