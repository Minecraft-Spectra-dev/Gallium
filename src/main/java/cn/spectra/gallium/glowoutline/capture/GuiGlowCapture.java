package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

public class GuiGlowCapture {
    public ItemEffectConfig config;
    public GpuTextureView atlasTextureView;
    public Matrix3x2fc pose;
    public int x, y;
    public float u0, u1, v0, v1;
    @Nullable public ScreenRectangle scissorArea;

    public void set(ItemEffectConfig config, GpuTextureView atlasTextureView, Matrix3x2fc pose,
                    int x, int y, float u0, float u1, float v0, float v1,
                    @Nullable ScreenRectangle scissorArea) {
        this.config = config;
        this.atlasTextureView = atlasTextureView;
        this.pose = pose;
        this.x = x;
        this.y = y;
        this.u0 = u0;
        this.u1 = u1;
        this.v0 = v0;
        this.v1 = v1;
        this.scissorArea = scissorArea;
    }

    public void reset() {
        this.config = null;
        this.atlasTextureView = null;
        this.pose = null;
        this.scissorArea = null;
    }
}
