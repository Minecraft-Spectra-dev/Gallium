package cn.spectra.gallium.mixin;

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

    @Inject(method = "writeAsPNG", at = @At("TAIL"))
    private static void galliumAfterDump(Path dir, String prefix, GpuTexture texture, int levels, IntUnaryOperator sizeMapper, CallbackInfo ci) {
        ResourceDumpCompressor.scheduleCompress();
    }
}
