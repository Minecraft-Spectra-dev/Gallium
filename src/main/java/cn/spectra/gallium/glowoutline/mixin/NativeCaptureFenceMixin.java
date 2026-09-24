package cn.spectra.gallium.glowoutline.mixin;

//#if MC>=1_21_06 && MC<1_26_02
import cn.spectra.gallium.glowoutline.capture.NativeCaptureFences;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoder;
import net.minecraft.client.renderer.MappableRingBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MappableRingBuffer.class)
public abstract class NativeCaptureFenceMixin {
    @WrapOperation(method = "rotate", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createFence()Lcom/mojang/blaze3d/buffers/GpuFence;",
            remap = false))
    private GpuFence galliumShareCaptureCompletion(CommandEncoder encoder, Operation<GpuFence> original) {
        var shared = NativeCaptureFences.createForRing(encoder);
        return shared == null ? original.call(encoder) : shared;
    }
}
//#else
//$$ public abstract class NativeCaptureFenceMixin {}
//#endif
