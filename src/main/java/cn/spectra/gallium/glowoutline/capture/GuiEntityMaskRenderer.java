package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.IrisCompat;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/** Replay at the original UI projection against the completed entity's depth, without world policy. */
final class GuiEntityMaskRenderer {
    private GuiEntityMaskRenderer() {}

    static void render(GlowCaptureState state, @Nullable TextureTarget scene) {
        if (state.guiEntity == null || scene == null || state.maskTarget == null
                || !state.capturedProjectionMatrix4fValid || !state.capturedModelViewMatrixValid
                || state.capturedProjectionType == null
                || !state.beginOrdinaryReplayAttempt(state.captureEpoch)) return;
        TextureTarget mask = state.maskTarget;
        var iris = IrisCompat.setBypass(true);
        try {
            //#if MC>=1_21_05
            var encoder = RenderSystem.getDevice().createCommandEncoder();
            //#if MC>=1_26_02
            //$$ // Vector4f() defaults W to one; an empty mask must have alpha zero.
            //$$ encoder.clearColorTexture(mask.getColorTexture(), new org.joml.Vector4f(0.0f));
            //#else
            encoder.clearColorTexture(mask.getColorTexture(), 0);
            //#endif
            encoder.copyTextureToTexture(scene.getDepthTexture(), mask.getDepthTexture(),
                    0, 0, 0, 0, 0, mask.width, mask.height);
            //#if MC>=1_21_06
            var oldColor = RenderSystem.outputColorTextureOverride;
            var oldDepth = RenderSystem.outputDepthTextureOverride;
            var projection = RenderSystem.getProjectionMatrixBuffer();
            var projectionType = RenderSystem.getProjectionType();
            //#else
            //$$ var projection = new Matrix4f(RenderSystem.getProjectionMatrix());
            //$$ var projectionType = RenderSystem.getProjectionType();
            //#endif
            cn.spectra.gallium.glowoutline.capture.LegacyModelView.push();
            try {
                //#if MC>=1_21_06
                RenderSystem.outputColorTextureOverride = mask.getColorTextureView();
                RenderSystem.outputDepthTextureOverride = mask.getDepthTextureView();
                RenderSystem.setProjectionMatrix(state.capturedProjectionMatrix, state.capturedProjectionType);
                //#else
                //$$ RenderSystem.setProjectionMatrix(state.capturedProjectionMatrix4f, state.capturedProjectionType);
                //#endif
                cn.spectra.gallium.glowoutline.capture.LegacyModelView.set(state.capturedModelViewMatrix);
                //#if MC>=1_21_09
                //#if MC>=1_26_02
                //$$ state.captureDispatcher.renderAllFeatures(state.captureStorage);
                //$$ state.captureStorageClean = true;
                //#else
                state.captureDispatcher.renderAllFeatures();
                state.guiEntity.buffers().bufferSource().endBatch();
                state.guiEntity.buffers().outlineBufferSource().endOutlineBatch();
                //#endif
                //#else
                //#if MC>=1_21_06
                //$$ state.customBufferSource.flush();
                //#else
                //$$ state.customBufferSource.flushToTarget(mask);
                //#endif
                //#endif
            } finally {
                cn.spectra.gallium.glowoutline.capture.LegacyModelView.pop();
                RenderSystem.setProjectionMatrix(projection, projectionType);
                //#if MC>=1_21_06
                RenderSystem.outputColorTextureOverride = oldColor;
                RenderSystem.outputDepthTextureOverride = oldDepth;
                //#endif
            }
            //#else
            //$$ int drawFramebuffer = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            //$$ int readFramebuffer = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);
            //$$ int[] viewport = new int[4];
            //$$ org.lwjgl.opengl.GL11.glGetIntegerv(org.lwjgl.opengl.GL11.GL_VIEWPORT, viewport);
            //$$ Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
            //#if MC>=1_21_02
            //$$ var projectionType = RenderSystem.getProjectionType();
            //#else
            //$$ var projectionType = RenderSystem.getVertexSorting();
            //#endif
            //$$ cn.spectra.gallium.glowoutline.capture.LegacyModelView.push();
            //$$ try {
            //$$     mask.setClearColor(0, 0, 0, 0);
            //#if MC>=1_21_02
            //$$     mask.clear();
            //#else
            //$$     mask.clear(net.minecraft.client.Minecraft.ON_OSX);
            //#endif
            //$$     mask.copyDepthFrom(scene);
            //$$     mask.bindWrite(true);
            //$$     RenderSystem.setProjectionMatrix(state.capturedProjectionMatrix4f, state.capturedProjectionType);
            //$$     cn.spectra.gallium.glowoutline.capture.LegacyModelView.set(state.capturedModelViewMatrix);
            //$$     state.customBufferSource.flushToTarget(mask);
            //$$ } finally {
            //$$     cn.spectra.gallium.glowoutline.capture.LegacyModelView.pop();
            //$$     RenderSystem.setProjectionMatrix(projection, projectionType);
            //$$     com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(36008, readFramebuffer);
            //$$     com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(36009, drawFramebuffer);
            //$$     RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            //$$ }
            //#endif
            state.maskDepthPrepared = true;
            state.maskPreparedThisFrame = true;
            state.exactDepthAlignment = true;
        } finally {
            IrisCompat.restoreBypass(iris);
        }
    }
}
