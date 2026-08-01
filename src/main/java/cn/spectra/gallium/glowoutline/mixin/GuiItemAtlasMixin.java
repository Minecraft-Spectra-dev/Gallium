package cn.spectra.gallium.glowoutline.mixin;

//#if MC>=1_26_00
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.gui.render.GuiItemAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GuiItemAtlas.class)
public class GuiItemAtlasMixin {

    // 26.2 replaced TextureFormat with GpuFormat on GpuDevice.createTexture's 3rd param, so
    // the @At target descriptor differs by version. The modifier body is identical: OR the
    // COPY_SRC flag into the usage int so the GUI items atlas is copyable into the glow mask.
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            //#if MC>=1_26_02
            //$$ target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/lang/String;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;",
            //#else
            target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/lang/String;ILcom/mojang/blaze3d/textures/TextureFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;",
            //#endif
            // GpuDevice is unobfuscated (com.mojang.blaze3d.* is bytecode-named through the
            // mapping pipeline), so there's nothing to remap; remap=false silences the
            // "Unable to locate method mapping" warning on loom-remap builds.
            remap = false),
            index = 1)
    private int galliumAddCopySrcFlag(int usage) {
        return usage | GpuTexture.USAGE_COPY_SRC;
    }
}
//#elseif MC>=1_21_06
//$$ import com.mojang.blaze3d.textures.GpuTexture;
//$$ import net.minecraft.client.gui.render.GuiRenderer;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$ import org.spongepowered.asm.mixin.injection.ModifyArg;
//$$
//$$ /**
//$$  * 1.21.10/11: the GUI items atlas lives directly on GuiRenderer (no GuiItemAtlas class).
//$$  * It is created in createAtlasTextures with usage = COPY_DST | TEXTURE_BINDING (12).
//$$  * Gallium's GUI glow path copies that atlas into a mask render target, which needs the
//$$  * source texture to carry USAGE_COPY_SRC — so OR it into the usage flag. The depth
//$$  * texture (a second createTexture call in the same method) doesn't match this @ModifyArg
//$$  * because we pin the RGBA8 items-atlas call via ordinal=0.
//$$  */
//$$ @Mixin(GuiRenderer.class)
//$$ public class GuiItemAtlasMixin {
//$$
//$$     @ModifyArg(method = "createAtlasTextures", at = @At(value = "INVOKE",
//$$             target = "Lcom/mojang/blaze3d/systems/GpuDevice;createTexture(Ljava/lang/String;ILcom/mojang/blaze3d/textures/TextureFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;",
//$$             ordinal = 0,
//$$             // GpuDevice is unobfuscated (com.mojang.blaze3d.* is bytecode-named through the
//$$             // mapping pipeline), so there's nothing to remap; remap=false silences Mixin AP's
//$$             // "Unable to locate method mapping" warning on the loom-remap 1.21.10/11 builds.
//$$             remap = false),
//$$             index = 1)
//$$     private int galliumAddCopySrcFlag(int usage) {
//$$         return usage | GpuTexture.USAGE_COPY_SRC;
//$$     }
//$$ }
//#else
//$$ public class GuiItemAtlasMixin {}
//#endif
