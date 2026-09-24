package cn.spectra.gallium.glowoutline.capture;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/** Only the inspected Iris route actually performs the encoder's default-framebuffer unbind. */
final class IrisFramebufferRoute {
    private static final MethodHandle MULTIPLY, SHADOW;
    static {
        MethodHandle multiply=null,shadow=null;
        try {
            var lookup=MethodHandles.lookup();
            multiply=lookup.unreflectGetter(Class.forName("net.irisshaders.iris.vertices.ImmediateState").getField("safeToMultiply"));
            shadow=lookup.unreflect(Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState").getMethod("areShadowsCurrentlyBeingRendered"));
        } catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable) {
            multiply=null;shadow=null;
        }
        MULTIPLY=multiply;SHADOW=shadow;
    }
    private IrisFramebufferRoute() {}
    static boolean nativeRoute() {
        if(MULTIPLY==null || SHADOW==null)return false;
        try {return !(boolean)MULTIPLY.invokeExact() && !(boolean)SHADOW.invokeExact();}
        catch(Throwable unavailable){return false;}
    }
}
