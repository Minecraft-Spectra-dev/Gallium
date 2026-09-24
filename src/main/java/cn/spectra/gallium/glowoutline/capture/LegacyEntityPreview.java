package cn.spectra.gallium.glowoutline.capture;

//#if MC<1_21_06
//$$ import cn.spectra.gallium.Gallium;
//$$ import cn.spectra.gallium.glowoutline.GlowOutlineConfig;
//$$ import cn.spectra.gallium.glowoutline.ItemEffectsManager;
//$$ import cn.spectra.gallium.glowoutline.shader.GlowResources;
//$$ import com.mojang.blaze3d.pipeline.RenderTarget;
//$$ import com.mojang.blaze3d.pipeline.TextureTarget;
//#if MC>=1_21_05
//$$ import com.mojang.blaze3d.opengl.GlStateManager;
//#else
//$$ import com.mojang.blaze3d.platform.GlStateManager;
//#endif
//$$ import com.mojang.blaze3d.systems.RenderSystem;
//$$ import com.mojang.blaze3d.vertex.BufferBuilder;
//$$ import com.mojang.blaze3d.vertex.DefaultVertexFormat;
//$$ import com.mojang.blaze3d.vertex.VertexFormat;
//$$ import net.minecraft.client.Minecraft;
//$$ import net.minecraft.client.gui.GuiGraphics;
//$$ import org.joml.Matrix4f;
//$$ import org.jspecify.annotations.Nullable;
//$$ import org.lwjgl.opengl.GL11;
//#if MC>=1_21_05
//$$ import com.mojang.blaze3d.pipeline.RenderPipeline;
//$$ import com.mojang.blaze3d.pipeline.BlendFunction;
//$$ import com.mojang.blaze3d.platform.DepthTestFunction;
//$$ import com.mojang.blaze3d.textures.FilterMode;
//$$ import com.mojang.blaze3d.textures.AddressMode;
//$$ import com.mojang.blaze3d.vertex.ByteBufferBuilder;
//$$ import net.minecraft.client.renderer.RenderPipelines;
//$$ import java.util.OptionalInt;
//#else
//$$ import com.mojang.blaze3d.vertex.BufferUploader;
//$$ import com.mojang.blaze3d.vertex.Tesselator;
//#endif
//$$
//$$ /** The immediate-mode inventory equivalent of vanilla's later PIP entity renderer. */
//$$ public final class LegacyEntityPreview {
//$$     private static @Nullable GuiEntityGlowCapture active;
//#if MC>=1_21_05
//$$     private static @Nullable RenderPipeline blitPipeline;
//$$     private static @Nullable ByteBufferBuilder quadBytes;
//$$     static {
//$$         GlowResources.register(() -> { if (quadBytes != null) quadBytes.close(); quadBytes = null; });
//$$         GlowResources.registerPipeline(() -> blitPipeline = null);
//$$     }
//#endif
//$$     private LegacyEntityPreview() {}
//$$     public static @Nullable GuiEntityGlowCapture active() { return active; }
//$$
//$$     public static void render(GuiGraphics graphics, float scale, Runnable original) {
//$$         // A preview scope is established even when disabled so its equipment can never leak into
//$$         // next frame's world capture queue. Pending ordinary GUI items are flushed before redirect.
//$$         graphics.flush();
//$$         Minecraft mc = Minecraft.getInstance();
//$$         RenderTarget main = mc.getMainRenderTarget();
//$$         var saved = new SavedState();
//$$         PreviewGeometry.Region region = saved.region(main.width, main.height);
//$$         boolean enabled = region.valid() && ItemEffectsManager.isActive() && GlowOutlineConfig.isEnabled()
//$$                 && (GlowOutlineConfig.isArmor() || GlowOutlineConfig.isThirdPerson() || GlowOutlineConfig.isOtherEntities());
//$$         GuiEntityGlowCapture parent = active;
//$$         // A disabled nested preview must not redirect its geometry into its parent.
//$$         active = null;
//$$         try (var preview = GuiEntityGlowCapture.begin(enabled ? region.width() : 0,
//$$                 enabled ? region.height() : 0, scale * (float) mc.getWindow().getGuiScale())) {
//$$             if (!enabled) { original.run(); return; }
//$$             TextureTarget target;
//$$             try {
//$$                 target = preview.target();
//$$             } catch (RuntimeException failure) {
//$$                 Gallium.LOGGER.warn("Unable to allocate entity preview target", failure);
//$$                 original.run();
//$$                 return;
//$$             }
//$$             RenderSystem.disableScissor();
//$$             RenderSystem.setProjectionMatrix(PreviewGeometry.crop(saved.projection, region, main.width, main.height),
//$$                     saved.projectionType);
//#if MC>=1_21_05
//$$             RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
//$$                     target.getColorTexture(), 0, target.getDepthTexture(), 1.0);
//#else
//$$             target.setClearColor(0, 0, 0, 0);
//#if MC>=1_21_02
//$$             target.clear();
//#else
//$$             target.clear(Minecraft.ON_OSX);
//#endif
//#endif
//$$             active = preview;
//$$             original.run();
//$$             // The entity invocation has already flushed its complete geometry into target.
//$$             try {
//$$                 preview.complete();
//$$             } catch (RuntimeException failure) {
//$$                 Gallium.LOGGER.warn("Unable to composite legacy entity preview glow", failure);
//$$             }
//$$             saved.restore();
//$$             blit(target, main, region);
//$$         } finally {
//$$             active = parent;
//$$             saved.restore();
//$$         }
//$$     }
//$$
//$$     private static void blit(TextureTarget target, RenderTarget main, PreviewGeometry.Region region) {
//$$         Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
//#if MC>=1_21_02
//$$         var projectionType = RenderSystem.getProjectionType();
//$$         RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, main.width, main.height, 0, -1, 1),
//$$                 com.mojang.blaze3d.ProjectionType.ORTHOGRAPHIC);
//#else
//$$         var projectionType = RenderSystem.getVertexSorting();
//$$         RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, main.width, main.height, 0, -1, 1),
//$$                 com.mojang.blaze3d.vertex.VertexSorting.ORTHOGRAPHIC_Z);
//#endif
//$$         cn.spectra.gallium.glowoutline.capture.LegacyModelView.push();
//$$         cn.spectra.gallium.glowoutline.capture.LegacyModelView.identity();
//$$         float x0 = region.x(), x1 = x0 + region.width();
//$$         float y0 = main.height - region.y() - region.height(), y1 = y0 + region.height();
//$$         try {
//#if MC>=1_21_05
//$$             if (blitPipeline == null) {
//$$                 blitPipeline = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
//$$                         .withLocation("gallium/entity_preview_blit")
//$$                         .withFragmentShader(cn.spectra.gallium.glowoutline.LegacyResourceIds.create(
//$$                                 "gallium", "core/internal/entity_preview_blit_gui_legacy"))
//$$                         .withBlend(new BlendFunction(com.mojang.blaze3d.platform.SourceFactor.ONE,
//$$                                 com.mojang.blaze3d.platform.DestFactor.ONE_MINUS_SRC_ALPHA,
//$$                                 com.mojang.blaze3d.platform.SourceFactor.ONE,
//$$                                 com.mojang.blaze3d.platform.DestFactor.ONE_MINUS_SRC_ALPHA))
//$$                         .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false)
//$$                         .withCull(false).build();
//$$             }
//$$             if (quadBytes == null) quadBytes = new ByteBufferBuilder(256);
//$$             BufferBuilder builder = new BufferBuilder(quadBytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
//$$             quad(builder, x0, y0, x1, y1);
//$$             try (var mesh = builder.build()) {
//$$                 if (mesh == null) return;
//$$                 var data = mesh.drawState();
//$$                 var vertices = data.format().uploadImmediateVertexBuffer(mesh.vertexBuffer());
//$$                 var indices = RenderSystem.getSequentialBuffer(data.mode());
//$$                 var indexBuffer = indices.getBuffer(data.indexCount());
//$$                 var color = target.getColorTexture();
//$$                 color.setTextureFilter(FilterMode.NEAREST, false);
//$$                 color.setAddressMode(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE);
//$$                 var encoder = RenderSystem.getDevice().createCommandEncoder();
//$$                 try (var pass = encoder.createRenderPass(main.getColorTexture(), OptionalInt.empty())) {
//$$                     pass.setPipeline(blitPipeline);
//$$                     pass.bindSampler("Sampler0", color);
//$$                     pass.setUniform("ColorModulator", 1f, 1f, 1f, 1f);
//$$                     pass.setVertexBuffer(0, vertices);
//$$                     pass.setIndexBuffer(indexBuffer, indices.type());
//$$                     pass.drawIndexed(0, data.indexCount());
//$$                 }
//$$             }
//#else
//$$             main.bindWrite(true);
//$$             RenderSystem.disableDepthTest();
//$$             RenderSystem.depthMask(false);
//$$             RenderSystem.disableCull();
//$$             RenderSystem.enableBlend();
//$$             RenderSystem.blendFuncSeparate(1, 771, 1, 771);
//$$             var shader = RenderSystem.getShader();
//$$             var program = cn.spectra.gallium.glowoutline.shader.GlowPipeline.getOrCreate(
//$$                     new cn.spectra.gallium.glowoutline.ItemEffectConfig("internal/entity_preview_blit_gl", java.util.List.of()));
//$$             if (program == null) return;
//$$             int foreground = cn.spectra.gallium.glowoutline.shader.GlowComposite.previewForegroundTexture();
//#if MC>=1_21_02
//$$             program.bindSampler("DiffuseSampler", target.getColorTextureId());
//$$             program.bindSampler(cn.spectra.gallium.glowoutline.shader.WorldGlowShader.FOREGROUND_SAMPLER, foreground);
//$$             RenderSystem.setShader(program);
//#else
//$$             program.setSampler("DiffuseSampler", target.getColorTextureId());
//$$             program.setSampler(cn.spectra.gallium.glowoutline.shader.WorldGlowShader.FOREGROUND_SAMPLER, foreground);
//$$             RenderSystem.setShader(() -> program);
//#endif
//$$             program.safeGetUniform("ScreenSize").set((float) main.width, (float) main.height);
//$$             program.safeGetUniform("ShaderAlign").set(x0, y0, x1 - x0, y1 - y0);
//$$             try {
//$$                 BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
//$$                 builder.addVertex(x0, y0, 0);
//$$                 builder.addVertex(x0, y1, 0);
//$$                 builder.addVertex(x1, y1, 0);
//$$                 builder.addVertex(x1, y0, 0);
//$$                 main.bindWrite(true);
//$$                 BufferUploader.drawWithShader(builder.buildOrThrow());
//$$             } finally {
//#if MC>=1_21_02
//$$                 RenderSystem.setShader(shader);
//#else
//$$                 RenderSystem.setShader(() -> shader);
//#endif
//$$             }
//#endif
//$$         } finally {
//$$             cn.spectra.gallium.glowoutline.capture.LegacyModelView.pop();
//$$             RenderSystem.setProjectionMatrix(projection, projectionType);
//$$         }
//$$     }
//$$
//$$     private static void quad(BufferBuilder builder, float x0, float y0, float x1, float y1) {
//$$         builder.addVertex(x0, y0, 0).setUv(0, 1).setColor(-1);
//$$         builder.addVertex(x0, y1, 0).setUv(0, 0).setColor(-1);
//$$         builder.addVertex(x1, y1, 0).setUv(1, 0).setColor(-1);
//$$         builder.addVertex(x1, y0, 0).setUv(1, 1).setColor(-1);
//$$     }
//$$
//$$     private static final class SavedState {
//$$         final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
//#if MC>=1_21_02
//$$         final com.mojang.blaze3d.ProjectionType projectionType = RenderSystem.getProjectionType();
//#else
//$$         final com.mojang.blaze3d.vertex.VertexSorting projectionType = RenderSystem.getVertexSorting();
//#endif
//$$         final int readFramebuffer = GL11.glGetInteger(36010);
//$$         final int drawFramebuffer = GL11.glGetInteger(36006);
//$$         final int[] viewport = new int[4];
//$$         final int[] scissor = new int[4];
//$$         final boolean scissorEnabled;
//$$         final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
//$$         final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
//$$         final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
//$$         final boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
//$$         final int sourceRgb = GL11.glGetInteger(32969), destRgb = GL11.glGetInteger(32968);
//$$         final int sourceAlpha = GL11.glGetInteger(32971), destAlpha = GL11.glGetInteger(32970);
//$$
//$$         SavedState() {
//$$             GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
//#if MC>=1_21_05
//$$             var s = RenderSystem.SCISSOR_STATE;
//$$             scissorEnabled = s.isEnabled();
//$$             scissor[0] = s.getX(); scissor[1] = s.getY();
//$$             scissor[2] = s.getWidth(); scissor[3] = s.getHeight();
//#else
//$$             scissorEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
//$$             GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissor);
//#endif
//$$         }
//$$
//$$         PreviewGeometry.Region region(int width, int height) {
//$$             return scissorEnabled
//$$                     ? PreviewGeometry.clip(scissor[0], scissor[1], scissor[2], scissor[3], width, height)
//$$                     : PreviewGeometry.clip(viewport[0], viewport[1], viewport[2], viewport[3], width, height);
//$$         }
//$$
//$$         void restore() {
//$$             RenderSystem.setProjectionMatrix(projection, projectionType);
//$$             if (scissorEnabled) RenderSystem.enableScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
//$$             else RenderSystem.disableScissor();
//$$             GlStateManager._glBindFramebuffer(36008, readFramebuffer);
//$$             GlStateManager._glBindFramebuffer(36009, drawFramebuffer);
//#if MC>=1_21_05
//$$             GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
//$$             // The command encoder owns pipeline state in 1.21.5; only restore direct GL
//$$             // state through its cached low-level implementation at this boundary.
//$$             if (blend) GlStateManager._enableBlend(); else GlStateManager._disableBlend();
//$$             if (depth) GlStateManager._enableDepthTest(); else GlStateManager._disableDepthTest();
//$$             if (cull) GlStateManager._enableCull(); else GlStateManager._disableCull();
//$$             GlStateManager._depthMask(depthWrite);
//$$             GlStateManager.glBlendFuncSeparate(sourceRgb, destRgb, sourceAlpha, destAlpha);
//#else
//$$             RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
//$$             if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
//$$             if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
//$$             if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
//$$             RenderSystem.depthMask(depthWrite);
//$$             RenderSystem.blendFuncSeparate(sourceRgb, destRgb, sourceAlpha, destAlpha);
//#endif
//$$         }
//$$     }
//$$ }
//#else
public final class LegacyEntityPreview {}
//#endif
