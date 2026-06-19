package cn.spectra.gallium.glowoutline.mixin;

//#if MC<1_26_00
//#if MC>=1_21_06
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
//$$ import cn.spectra.gallium.glowoutline.ItemEffectConfig;
//$$ import cn.spectra.gallium.glowoutline.ItemEffectsManager;
//$$ import cn.spectra.gallium.glowoutline.capture.GuiItemRenderStateAccessor;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import net.minecraft.client.gui.GuiGraphics;
//$$ import net.minecraft.client.gui.navigation.ScreenRectangle;
//$$ import net.minecraft.client.gui.render.state.GuiItemRenderState;
//$$ import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
//$$ import net.minecraft.world.entity.LivingEntity;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import net.minecraft.world.level.Level;
//$$ import org.joml.Matrix3x2f;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$
//$$ /**
//$$  * 1.21.11 has no GuiGraphicsExtractor — GUI item entry happens directly inside
//$$  * GuiGraphics.renderItem(LivingEntity, Level, ItemStack, int, int, int).
//$$  * We hook the {@code new GuiItemRenderState(...)} construction there so we can
//$$  * stamp the per-item ItemEffectConfig into the state via the side-channel
//$$  * accessor, mirroring what GuiGraphicsExtractorMixin does on 26.1.
//$$  */
//$$ @Mixin(GuiGraphics.class)
//$$ public class GuiGraphicsItemMixin {
//$$
//$$     @WrapOperation(method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V",
//$$             at = @At(value = "NEW",
//$$                     target = "(Ljava/lang/String;Lorg/joml/Matrix3x2f;Lnet/minecraft/client/renderer/item/TrackingItemStackRenderState;IILnet/minecraft/client/gui/navigation/ScreenRectangle;)Lnet/minecraft/client/gui/render/state/GuiItemRenderState;"))
//$$     private GuiItemRenderState galliumAttachConfig(String name, Matrix3x2f pose, TrackingItemStackRenderState renderState, int x, int y, ScreenRectangle scissor,
//$$                                                     Operation<GuiItemRenderState> original,
//$$                                                     LivingEntity owner, Level level, ItemStack itemStack, int xArg, int yArg, int seed) {
//$$         GuiItemRenderState state = original.call(name, pose, renderState, x, y, scissor);
//$$         if (!ItemEffectsManager.isActive() || !GlowOutlineConfig.isEnabled() || !GlowOutlineConfig.isGui()) return state;
//$$         ItemEffectConfig cfg = ItemEffectsManager.getConfig(itemStack);
//$$         if (cfg != null && !cfg.shader().isEmpty()) {
//$$             ((GuiItemRenderStateAccessor) (Object) state).gallium$setEffectConfig(cfg);
//$$         }
//$$         return state;
//$$     }
//$$ }
//#elseif MC>=1_21_04
//$$ import cn.spectra.gallium.Gallium;
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
//$$ import cn.spectra.gallium.glowoutline.ItemEffectConfig;
//$$ import cn.spectra.gallium.glowoutline.ItemEffectsManager;
//$$ import cn.spectra.gallium.glowoutline.capture.CaptureSites;
//$$ import cn.spectra.gallium.glowoutline.shader.GlowResources;
//$$ import cn.spectra.gallium.glowoutline.shader.GuiImmediateGlowComposite;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import net.minecraft.client.gui.GuiGraphics;
//$$ import net.minecraft.client.renderer.MultiBufferSource;
//$$ import net.minecraft.client.renderer.item.ItemStackRenderState;
//$$ import net.minecraft.world.entity.LivingEntity;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import net.minecraft.world.level.Level;
//$$ import org.joml.Matrix4f;
//$$ import org.jspecify.annotations.Nullable;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$
//$$ /**
//$$  * 1.21.5 GUI item glow capture site — per-item composite, mirroring 1.21.6+'s
//$$  * atlas-tile + alpha-ring approach to keep the visual outline identical across
//$$  * versions despite 1.21.5 lacking the deferred GUI render-state system.
//$$  *
//$$  * <p>Flow inside {@code GuiGraphics.renderItem(7-arg private)}:
//$$  * <ol>
//$$  *   <li>{@link WrapOperation} intercepts the inner
//$$  *       {@code scratchItemStackRenderState.render(pose, bufferSource, ...)} call.</li>
//$$  *   <li>If the stack has glow config, the original {@code bufferSource} is replaced
//$$  *       with a tee'd one that duplicates non-glint vertices into a pooled
//$$  *       {@link CaptureSites.DelayingMultiBufferSource}. Vanilla still draws to
//$$  *       mainTarget (the first stream of the tee).</li>
//$$  *   <li>After {@code original.call} returns, the captured mesh is replayed into
//$$  *       {@code GuiImmediateGlowTile}'s small off-screen FBO under a custom ortho mapping
//$$  *       the item's GUI screen rect to the tile, then a glow quad is drawn on
//$$  *       mainTarget that samples the tile through {@code core/<shader>_gui.fsh}.</li>
//$$  * </ol>
//$$  *
//$$  * <p>Per-item composite (rather than frame-end batched) gives correct z-order with
//$$  * subsequent GUI elements: tooltips / scoreboards / toasts rendered after this item's
//$$  * call automatically cover the outline because they paint over mainTarget at later
//$$  * GUI draws.
//$$  *
//$$  * <p>The captured mesh buffer is pooled across calls — a single shared
//$$  * {@link CaptureSites.DelayingMultiBufferSource} instance is reused, with its native
//$$  * buffers automatically rewound after each {@code flushToTarget}. Render thread is
//$$  * single-threaded, so no synchronization needed; recursive renderItem (e.g. tooltip
//$$  * preview during inventory rendering) would clobber state, but vanilla doesn't do that
//$$  * — items render in flat sequence.
//$$  */
//$$ @Mixin(GuiGraphics.class)
//$$ public class GuiGraphicsItemMixin {
//$$
//$$     // Single shared capture buffer. Lazy-allocated on first glowing item; the pooled
//$$     // off-heap ByteBufferBuilders inside (one per RenderType seen) survive across
//$$     // resource reloads — released only on JVM exit. ~256 KiB per layer × ~3 typical
//$$     // RenderTypes (item_entity_translucent_cull, item_entity_solid, ...) = under 1 MiB
//$$     // of fixed VRAM/RAM overhead for the GUI glow path.
//$$     // Idempotent disposer so resource reload frees the pooled native buffers and drops stale RenderType refs.
//$$     private static CaptureSites.@Nullable DelayingMultiBufferSource gallium$pooledBuf;
//$$
//$$     static {
//$$         GlowResources.register(() -> {
//$$             if (gallium$pooledBuf != null) {
//$$                 gallium$pooledBuf.free();
//$$                 gallium$pooledBuf = null;
//$$             }
//$$         });
//$$     }
//$$
//$$     /**
//$$      * Intercept the inner {@code ItemStackRenderState.render(pose, bufferSource, ...)}
//$$      * call inside the 7-arg {@code renderItem}. Public 6-arg overloads delegate to the
//$$      * 7-arg, so this single hook covers every GUI-renderItem entrypoint.
//$$      */
//$$     @WrapOperation(method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;IIII)V",
//$$             at = @At(value = "INVOKE",
//$$                     target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V"))
//$$     private void galliumGuiWrapRender(ItemStackRenderState self, PoseStack pose,
//$$                                        MultiBufferSource bufferSource, int light, int overlay,
//$$                                        Operation<Void> original,
//$$                                        LivingEntity owner, Level level, ItemStack stack,
//$$                                        int x, int y, int seed, int guiOffset) {
//$$         // Fast paths first — feature off, empty stack, no config, etc.
//$$         if (stack == null || stack.isEmpty()
//$$                 || !ItemEffectsManager.isActive()
//$$                 || !GlowOutlineConfig.isEnabled()
//$$                 || !GlowOutlineConfig.isGui()) {
//$$             original.call(self, pose, bufferSource, light, overlay);
//$$             return;
//$$         }
//$$         ItemEffectConfig cfg = ItemEffectsManager.getConfig(stack);
//$$         if (cfg == null || cfg.shader().isEmpty()) {
//$$             original.call(self, pose, bufferSource, light, overlay);
//$$             return;
//$$         }
//$$
//$$         // Lazy init the shared capture buffer.
//$$         CaptureSites.DelayingMultiBufferSource buf = gallium$pooledBuf;
//$$         if (buf == null) {
//$$             buf = new CaptureSites.DelayingMultiBufferSource();
//$$             gallium$pooledBuf = buf;
//$$         }
//$$
//$$         MultiBufferSource wrapped = CaptureSites.teeGuiNonGlint(bufferSource, buf);
//$$         try {
//$$             original.call(self, pose, wrapped, light, overlay);
//$$         } finally {
//$$             // Resolve the item's ABSOLUTE screen-px position from the pose matrix's
//$$             // translation column. Vanilla applies pose.translate(x+8, y+8, 150+l) and
//$$             // pose.scale(16, -16, 16) on top of whatever the screen pushed (e.g.
//$$             // AbstractContainerScreen.render does pose.translate(leftPos, topPos, 0)
//$$             // before passing slot-LOCAL coords to renderItem).
//$$             //
//$$             // The original "m30 - 8" formula assumed outerSx = 1 (unit-scale outer pose).
//$$             // True for hotbar/inventory/most modded GUIs. The general form below stays
//$$             // correct under any outer translate+scale — including hypothetical mods or
//$$             // future vanilla screens that scale the pose stack.
//$$             Matrix4f matrix = pose.last().pose();
//$$             float outerSx = matrix.m00() / 16f;
//$$             float outerSy = -matrix.m11() / 16f;
//$$             // Degenerate outer pose (zero scale) shouldn't happen — vanilla never scales
//$$             // by zero — but guard against div-by-zero by falling back to unit scale.
//$$             if (Math.abs(outerSx) < 1e-6f) outerSx = 1f;
//$$             if (Math.abs(outerSy) < 1e-6f) outerSy = 1f;
//$$             int absItemX = Math.round(matrix.m30() - 8f * outerSx);
//$$             int absItemY = Math.round(matrix.m31() - 8f * outerSy);
//$$
//$$             try {
//$$                 GuiImmediateGlowComposite.composeForItem(cfg, buf, absItemX, absItemY);
//$$             } catch (Throwable t) {
//$$                 Gallium.LOGGER.error("GuiImmediateGlow compose failed for item {}: {}",
//$$                         stack.getItem(), t.toString(), t);
//$$             }
//$$             // composeForItem already builds and nulls every layer's BufferBuilder; endFrame
//$$             // is the safety net for the throw paths above so the next call sees a clean buf.
//$$             buf.endFrame();
//$$         }
//$$     }
//$$ }
//#else
//$$ // 1.21.3: no ItemStackRenderState yet — GUI items are drawn through either
//$$ // ItemRenderer.render(itemStack, GUI, false, pose, bufferSource, light, overlay, BakedModel)
//$$ // or, for #minecraft:bundles items, ItemRenderer.renderBundleItem(...). Both invokes live
//$$ // inside the same private GuiGraphics.renderItem, and exactly one runs per item (vanilla's
//$$ // if/else on the BUNDLES tag), so we wrap both to cover bundles too — matching the unified
//$$ // ItemStackRenderState path on 1.21.4+. Same per-item composite flow as the 1.21.5 branch
//$$ // (tee non-glint vertices into a pooled DelayingMultiBufferSource, then composeForItem),
//$$ // only the wrapped invoke shape differs.
//$$ import cn.spectra.gallium.Gallium;
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
//$$ import cn.spectra.gallium.glowoutline.ItemEffectConfig;
//$$ import cn.spectra.gallium.glowoutline.ItemEffectsManager;
//$$ import cn.spectra.gallium.glowoutline.capture.CaptureSites;
//$$ import cn.spectra.gallium.glowoutline.shader.GlowResources;
//$$ import cn.spectra.gallium.glowoutline.shader.GuiImmediateGlowComposite;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
//$$ import com.mojang.blaze3d.vertex.PoseStack;
//$$ import net.minecraft.client.gui.GuiGraphics;
//$$ import net.minecraft.client.renderer.MultiBufferSource;
//$$ import net.minecraft.client.renderer.entity.ItemRenderer;
//$$ import net.minecraft.client.resources.model.BakedModel;
//$$ import net.minecraft.world.entity.LivingEntity;
//$$ import net.minecraft.world.item.ItemDisplayContext;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import net.minecraft.world.level.Level;
//$$ import org.joml.Matrix4f;
//$$ import org.jspecify.annotations.Nullable;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.Unique;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$
//$$ @Mixin(GuiGraphics.class)
//$$ public class GuiGraphicsItemMixin {
//$$
//$$     private static CaptureSites.@Nullable DelayingMultiBufferSource gallium$pooledBuf;
//$$
//$$     static {
//$$         GlowResources.register(() -> {
//$$             if (gallium$pooledBuf != null) {
//$$                 gallium$pooledBuf.free();
//$$                 gallium$pooledBuf = null;
//$$             }
//$$         });
//$$     }
//$$
//$$     // Non-bundle items: vanilla calls ItemRenderer.render(..., BakedModel).
//$$     @WrapOperation(method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;IIII)V",
//$$             at = @At(value = "INVOKE",
//$$                     target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V"))
//$$     private void galliumGuiWrapRender(ItemRenderer renderer, ItemStack itemStack, ItemDisplayContext ctx, boolean leftHand,
//$$                                        PoseStack pose, MultiBufferSource bufferSource, int light, int overlay, BakedModel bakedModel,
//$$                                        Operation<Void> original,
//$$                                        LivingEntity owner, Level level, ItemStack stack,
//$$                                        int x, int y, int seed, int guiOffset) {
//$$         ItemEffectConfig cfg = gallium$beginGlow(stack);
//$$         if (cfg == null) {
//$$             original.call(renderer, itemStack, ctx, leftHand, pose, bufferSource, light, overlay, bakedModel);
//$$             return;
//$$         }
//$$         MultiBufferSource wrapped = CaptureSites.teeGuiNonGlint(bufferSource, gallium$pooledBuf);
//$$         try {
//$$             original.call(renderer, itemStack, ctx, leftHand, pose, wrapped, light, overlay, bakedModel);
//$$         } finally {
//$$             gallium$endGlow(cfg, stack, pose);
//$$         }
//$$     }
//$$
//$$     // #minecraft:bundles items take a different vanilla path: ItemRenderer.renderBundleItem(...).
//$$     // The wrapped buffer propagates through every inner draw (open back/front model + selected
//$$     // item), so the tee captures the whole composited bundle.
//$$     @WrapOperation(method = "renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;IIII)V",
//$$             at = @At(value = "INVOKE",
//$$                     target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderBundleItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;I)V"))
//$$     private void galliumGuiWrapBundleRender(ItemRenderer renderer, ItemStack itemStack, ItemDisplayContext ctx, boolean leftHand,
//$$                                              PoseStack pose, MultiBufferSource bufferSource, int light, int overlay, BakedModel bakedModel,
//$$                                              Level bundleLevel, LivingEntity bundleOwner, int bundleSeed,
//$$                                              Operation<Void> original,
//$$                                              LivingEntity owner, Level level, ItemStack stack,
//$$                                              int x, int y, int seed, int guiOffset) {
//$$         ItemEffectConfig cfg = gallium$beginGlow(stack);
//$$         if (cfg == null) {
//$$             original.call(renderer, itemStack, ctx, leftHand, pose, bufferSource, light, overlay, bakedModel, bundleLevel, bundleOwner, bundleSeed);
//$$             return;
//$$         }
//$$         MultiBufferSource wrapped = CaptureSites.teeGuiNonGlint(bufferSource, gallium$pooledBuf);
//$$         try {
//$$             original.call(renderer, itemStack, ctx, leftHand, pose, wrapped, light, overlay, bakedModel, bundleLevel, bundleOwner, bundleSeed);
//$$         } finally {
//$$             gallium$endGlow(cfg, stack, pose);
//$$         }
//$$     }
//$$
//$$     // Returns the effect config to glow with, or null if this item shouldn't glow. Lazily
//$$     // allocates the shared capture buffer as a side effect whenever it returns non-null.
//$$     @Unique
//$$     private ItemEffectConfig gallium$beginGlow(ItemStack stack) {
//$$         if (stack == null || stack.isEmpty()
//$$                 || !ItemEffectsManager.isActive()
//$$                 || !GlowOutlineConfig.isEnabled()
//$$                 || !GlowOutlineConfig.isGui()) {
//$$             return null;
//$$         }
//$$         ItemEffectConfig cfg = ItemEffectsManager.getConfig(stack);
//$$         if (cfg == null || cfg.shader().isEmpty()) return null;
//$$         if (gallium$pooledBuf == null) {
//$$             gallium$pooledBuf = new CaptureSites.DelayingMultiBufferSource();
//$$         }
//$$         return cfg;
//$$     }
//$$
//$$     // Replays the captured mesh into the off-screen tile and composites the outline onto
//$$     // mainTarget. Always rewinds the shared buffer (endFrame) so the next item sees it clean.
//$$     @Unique
//$$     private void gallium$endGlow(ItemEffectConfig cfg, ItemStack stack, PoseStack pose) {
//$$         CaptureSites.DelayingMultiBufferSource buf = gallium$pooledBuf;
//$$         // Same absolute-position derivation as the 1.21.5 branch — see comments there.
//$$         Matrix4f matrix = pose.last().pose();
//$$         float outerSx = matrix.m00() / 16f;
//$$         float outerSy = -matrix.m11() / 16f;
//$$         if (Math.abs(outerSx) < 1e-6f) outerSx = 1f;
//$$         if (Math.abs(outerSy) < 1e-6f) outerSy = 1f;
//$$         int absItemX = Math.round(matrix.m30() - 8f * outerSx);
//$$         int absItemY = Math.round(matrix.m31() - 8f * outerSy);
//$$         try {
//$$             GuiImmediateGlowComposite.composeForItem(cfg, buf, absItemX, absItemY);
//$$         } catch (Throwable t) {
//$$             Gallium.LOGGER.error("GuiImmediateGlow compose failed for item {}: {}",
//$$                     stack.getItem(), t.toString(), t);
//$$         }
//$$         buf.endFrame();
//$$     }
//$$ }
//#endif
//#else
public class GuiGraphicsItemMixin {}
//#endif
