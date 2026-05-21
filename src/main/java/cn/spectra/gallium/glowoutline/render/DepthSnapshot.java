package cn.spectra.gallium.glowoutline.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;

public final class DepthSnapshot {
    private DepthSnapshot() {}

    public static void capture(String label,
                                Supplier<TextureTarget> getter,
                                Consumer<TextureTarget> setter) {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (main == null || main.getDepthTexture() == null) return;
        int w = main.width, h = main.height;

        TextureTarget t = getter.get();
        if (t == null || t.width != w || t.height != h) {
            if (t != null) t.destroyBuffers();
            t = new TextureTarget(label, w, h, true);
            setter.accept(t);
        }
        DepthCopyHelper.copyDepthRawGL(main.getDepthTexture(), t.getDepthTexture(), w, h);
    }
}
