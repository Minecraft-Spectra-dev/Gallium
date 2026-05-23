package cn.spectra.gallium.glowoutline.mixin;

import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.gui.render.GuiItemAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GuiItemAtlas.class)
public class GuiItemAtlasMixin {

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/lang/String;ILcom/mojang/blaze3d/textures/TextureFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;"),
            index = 1)
    private int galliumAddCopySrcFlag(int usage) {
        return usage | GpuTexture.USAGE_COPY_SRC;
    }
}
