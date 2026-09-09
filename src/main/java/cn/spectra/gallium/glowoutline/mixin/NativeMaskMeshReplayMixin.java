package cn.spectra.gallium.glowoutline.mixin;

//#if MC==1_21_11 || MC==1_26_01
import cn.spectra.gallium.glowoutline.capture.NativeMaskMeshReplay;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderType.class)
public abstract class NativeMaskMeshReplayMixin {
    @Inject(method = "draw", at = @At(value = "INVOKE",
            target = "Lorg/joml/Matrix4fStack;pushMatrix()Lorg/joml/Matrix4fStack;", shift = At.Shift.AFTER, remap = false))
    private void galliumNativeLayerPushed(MeshData mesh, CallbackInfo callback) {
        NativeMaskMeshReplay.layerPushed();
    }

    @Inject(method = "draw", at = @At(value = "INVOKE",
            target = "Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;", shift = At.Shift.AFTER, remap = false))
    private void galliumNativeLayerPopped(MeshData mesh, CallbackInfo callback) {
        NativeMaskMeshReplay.layerPopped();
    }

    @Inject(method = "draw", at = @At("HEAD"))
    private void galliumRecordNativeMesh(MeshData mesh, CallbackInfo callback) {
        NativeMaskMeshReplay.record((RenderType) (Object) this, mesh);
    }
}
//#else
//$$ public abstract class NativeMaskMeshReplayMixin {}
//#endif
