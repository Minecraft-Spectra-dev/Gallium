package cn.spectra.gallium.glowoutline.shader;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;

/** Shared one-pixel far-depth fallback; no screen-size target or per-item copy is needed. */
final class ForegroundOcclusion {
    private static TextureTarget farTarget;
    static { GlowResources.register(ForegroundOcclusion::dispose); }
    private ForegroundOcclusion() {}

    static TextureTarget farTarget(
            //#if MC>=1_21_05
            com.mojang.blaze3d.systems.CommandEncoder encoder
            //#endif
    ) {
        if (farTarget != null) return farTarget;
        //#if MC>=1_21_05
        TextureTarget target = new TextureTarget("Gallium No Foreground", 1, 1, false
                //#if MC>=1_26_02
                //$$ , com.mojang.blaze3d.GpuFormat.RGBA8_UNORM
                //#endif
        );
        //#elseif MC>=1_21_02
        //$$ TextureTarget target = new TextureTarget(1, 1, false);
        //#else
        //$$ TextureTarget target = new TextureTarget(1, 1, false, net.minecraft.client.Minecraft.ON_OSX);
        //#endif
        try {
            //#if MC>=1_21_05
            //#if MC>=1_26_02
            //$$ encoder.clearColorTexture(target.getColorTexture(), new org.joml.Vector4f(1.0f));
            //#else
            encoder.clearColorTexture(target.getColorTexture(), -1);
            //#endif
            //#if MC==1_21_10
            //$$ target.getColorTexture().setUseMipmaps(false);
            //#endif
            //#else
            //$$ int bound = com.mojang.blaze3d.platform.GlStateManager._getInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
            //$$ try (var pixel = new com.mojang.blaze3d.platform.NativeImage(1, 1, false)) {
            //$$     RenderSystem.bindTexture(target.getColorTextureId());
            //#if MC>=1_21_02
            //$$     pixel.setPixel(0, 0, -1);
            //#else
            //$$     pixel.setPixelRGBA(0, 0, -1);
            //#endif
            //$$     pixel.upload(0, 0, 0, false);
            //$$ } finally {
            //$$     RenderSystem.bindTexture(bound);
            //$$ }
            //#endif
            farTarget = target;
            return target;
        } catch (RuntimeException | Error failure) {
            target.destroyBuffers();
            throw failure;
        }
    }

    private static void dispose() {
        if (farTarget != null) {
            farTarget.destroyBuffers();
            farTarget = null;
        }
    }
}
