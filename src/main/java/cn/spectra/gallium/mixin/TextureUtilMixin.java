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

    // TextureUtil is in com.mojang.blaze3d.* (unobfuscated). On loom-remap versions
    // (1.21.x) Mixin AP can't find a mapping entry for writeAsPNG and bails with
    // "Unable to locate obfuscation mapping". remap=false tells AP to skip lookup
    // since there's nothing to remap; the method is matched by literal name+desc
    // at runtime against the same bytecode on both versions.
    @Inject(method = "writeAsPNG(Ljava/nio/file/Path;Ljava/lang/String;Lcom/mojang/blaze3d/textures/GpuTexture;ILjava/util/function/IntUnaryOperator;)V",
            at = @At("TAIL"),
            remap = false)
    private static void galliumAfterDump(Path dir, String prefix, GpuTexture texture, int levels, IntUnaryOperator sizeMapper, CallbackInfo ci) {
        ResourceDumpCompressor.scheduleCompress();
    }
}
