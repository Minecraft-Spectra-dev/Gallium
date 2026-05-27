package cn.spectra.gallium.glowoutline.mixin;

//#if MC>=1_26_00
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
//#else
//$$ // 1.21.11 doesn't need USAGE_COPY_SRC on the items atlas: GuiGlowDispatcher samples the
//$$ // atlas directly through GuiRendererAccessor and never copies it to a separate mask
//$$ // target (GuiGlowRenderer.renderMaskOnly returns null on this version). Stripped from
//$$ // mixins.json on 1.21.11 via STUB_MIXIN_CLASSES_PRE_1_26 in common.gradle.
//$$ public class GuiItemAtlasMixin {}
//#endif
