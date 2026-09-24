package cn.spectra.gallium.glowoutline.mixin;

//#if MC>=1_21_06 && MC<1_26_02
import cn.spectra.gallium.glowoutline.capture.NativeCaptureFences;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;

/** Frame cleanup rotates the last native rings after GameRenderer has returned. */
@Mixin(Minecraft.class)
public abstract class NativeFrameFenceMixin {
    //#if MC==1_26_01
    @WrapMethod(method = "renderFrame")
    //#else
    //$$ @WrapMethod(method = "runTick")
    //#endif
    private void galliumShareFrameCompletion(boolean renderLevel, Operation<Void> original) {
        try (var completion = NativeCaptureFences.beginFrame()) {
            original.call(renderLevel);
        } finally {
            //#if MC==1_21_11 || MC==1_26_01
            cn.spectra.gallium.glowoutline.shader.NativeWorldVisibility.finishFrame();
            cn.spectra.gallium.glowoutline.capture.NativePrimarySources.finishFrame();
            cn.spectra.gallium.glowoutline.capture.NativeVisibilityBatch.finishFrame();
            cn.spectra.gallium.glowoutline.capture.NativeSourceCoverage.finishFrame();
            //#endif
        }
    }
}
//#else
//$$ public abstract class NativeFrameFenceMixin {}
//#endif
