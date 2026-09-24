package cn.spectra.gallium.glowoutline.capture;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
/** Model-view stack API compatibility; newer targets retain their native operations. */
public final class LegacyModelView {
    private LegacyModelView() {}
    public static void push() {
//#if MC>=1_20_05
        RenderSystem.getModelViewStack().pushMatrix();
//#else
//$$         RenderSystem.getModelViewStack().pushPose();
//#endif
    }
    public static void pop() {
//#if MC>=1_20_05
        RenderSystem.getModelViewStack().popMatrix();
//#else
//$$         RenderSystem.getModelViewStack().popPose();
//$$         RenderSystem.applyModelViewMatrix();
//#endif
    }
    public static void identity() {
//#if MC>=1_20_05
        RenderSystem.getModelViewStack().identity();
//#else
//$$         RenderSystem.getModelViewStack().setIdentity();
//$$         RenderSystem.applyModelViewMatrix();
//#endif
    }
    public static void set(Matrix4f matrix) {
//#if MC>=1_20_05
        RenderSystem.getModelViewStack().set(matrix);
//#else
//$$         RenderSystem.getModelViewStack().last().pose().set(matrix);
//$$         RenderSystem.applyModelViewMatrix();
//#endif
    }
}
