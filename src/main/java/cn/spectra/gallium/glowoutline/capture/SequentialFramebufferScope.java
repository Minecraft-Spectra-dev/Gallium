package cn.spectra.gallium.glowoutline.capture;

//#if MC>=1_21_05 && MC<1_26_02
import cn.spectra.gallium.glowoutline.IrisCompat;
import com.mojang.blaze3d.opengl.GlStateManager;
import org.lwjgl.opengl.GL30;

/** Defers the backend's default-framebuffer unbind until our sequence of native passes ends. */
public final class SequentialFramebufferScope implements AutoCloseable {
    private static SequentialFramebufferScope current;
    private final SequentialFramebufferScope parent;
    private final OpenGlMaskOrdering.Stamp ordering;
    private boolean deferred, closed;
    private final boolean srRoute, nativeRouteRequired;

    public SequentialFramebufferScope() { this(false); }

    /** allowIris requires the caller to verify the complete ordinary original-shader frame. */
    public SequentialFramebufferScope(boolean allowIris) { this(allowIris,false); }

    /** SR admission belongs to the complete verified display-space original-shader frame. */
    public SequentialFramebufferScope(boolean allowIris,boolean allowSr) {
        srRoute=IrisCompat.isActiveSrRuntime();
        nativeRouteRequired = srRoute
                //#if MC==1_21_10 || MC==1_21_11 || MC==1_26_01
                || IrisCompat.isShaderActive()
                //#endif
                ;
        ordering = (!IrisCompat.isShaderActive() || allowIris) && (!srRoute || allowSr)
                && (!nativeRouteRequired || IrisFramebufferRoute.nativeRoute())
                ? OpenGlMaskOrdering.observe() : null;
        parent = current;
        current = this;
    }

    public static boolean deferUnbind(int target, int framebuffer) {
        var scope = current;
        if (scope == null || scope.closed || scope.ordering == null || !scope.ordering.current()
                || target != GL30.GL_FRAMEBUFFER || framebuffer != 0
                || (scope.nativeRouteRequired && !IrisFramebufferRoute.nativeRoute())) return false;
        scope.deferred = true;
        return true;
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try {
            // Each subsequent pass binds its own attachments. At the sequence boundary,
            // restore precisely the default binding finishRenderPass would have installed.
            if (deferred) GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        } finally {
            if (current == this) current = parent;
        }
    }
}
//#else
//$$ public final class SequentialFramebufferScope { private SequentialFramebufferScope() {} }
//#endif
