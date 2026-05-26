package cn.spectra.gallium.mixin;

//#if MC>=1_26_00
import cn.spectra.gallium.dump.ResourceDumpCompressor;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.textures.GpuTexture;
import java.nio.file.Path;
import java.util.function.IntUnaryOperator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureUtil.class)
public class TextureUtilMixin {

    @Inject(method = "writeAsPNG(Ljava/nio/file/Path;Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTexture;ILjava/util/function/IntUnaryOperator;)V", at = @At("TAIL"))
    private static void galliumAfterDump(Path dir, String prefix, GpuTexture texture, int levels, IntUnaryOperator sizeMapper, CallbackInfo ci) {
        ResourceDumpCompressor.scheduleCompress();
    }
}
//#else
//$$ // 1.21.11's loom-remap mixin AP can't resolve writeAsPNG (a fully-named-through
//$$ // method on an unobfuscated class). Disabled until a workaround is found —
//$$ // resource dumps still write, just without auto-zipping on this version.
//$$ public class TextureUtilMixin {}
//#endif
