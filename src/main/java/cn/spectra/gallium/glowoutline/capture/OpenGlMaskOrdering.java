package cn.spectra.gallium.glowoutline.capture;

//#if MC==1_21_11 || MC==1_26_01
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
//#if MC==1_21_11
//$$ import com.mojang.blaze3d.opengl.GlDevice;
//$$ import com.mojang.blaze3d.opengl.GlCommandEncoder;
//#endif
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;

/**
 * Opt in only to the inspected, immediate OpenGL implementation, not a backend-name heuristic.
 * GlRenderPass.draw -> GlCommandEncoder.executeDraw -> GL draw; close returns after encoding.
 * OpenGL 4.6 §2.1 orders framebuffer writes, later texture reads, and subsequent framebuffer
 * clears in one context. No shared-image feedback loop or shader-image store is used here.
 */
public final class OpenGlMaskOrdering {
    //#if MC==1_26_01
    private static final Field DEVICE_BACKEND = field(GpuDevice.class, "backend");
    private static final Field ENCODER_BACKEND = field(CommandEncoder.class, "backend");
    //#endif

    private OpenGlMaskOrdering() {}

    //#if MC==1_26_01
    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            return field.trySetAccessible() ? field : null;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
        }
    }
    //#endif

    private static boolean matchesInspectedBackend(GpuDevice device, CommandEncoder encoder)
            throws ReflectiveOperationException {
        //#if MC==1_26_01
        if (DEVICE_BACKEND == null || ENCODER_BACKEND == null
                || device.getClass() != GpuDevice.class || encoder.getClass() != CommandEncoder.class) return false;
        Object nativeDevice = DEVICE_BACKEND.get(device), nativeEncoder = ENCODER_BACKEND.get(encoder);
        return nativeDevice != null && nativeEncoder != null
                && nativeDevice.getClass().getName().equals("com.mojang.blaze3d.opengl.GlDevice")
                && nativeEncoder.getClass().getName().equals("com.mojang.blaze3d.opengl.GlCommandEncoder");
        //#else
        //$$ // 1.21.11 exposes direct implementations. Class literals also survive production remapping.
        //$$ return device.getClass() == GlDevice.class && encoder.getClass() == GlCommandEncoder.class;
        //#endif
    }

    public record Stamp(Object device, long context) {
        public boolean current() {
            return RenderSystem.isOnRenderThread() && RenderSystem.getDevice() == device
                    && context != 0L && GLFW.glfwGetCurrentContext() == context;
        }
    }

    public static Stamp observe() {
        try {
            if (!RenderSystem.isOnRenderThread()) return null;
            GpuDevice device = RenderSystem.getDevice();
            CommandEncoder encoder = device.createCommandEncoder();
            if (!matchesInspectedBackend(device, encoder)) return null;
            long context = GLFW.glfwGetCurrentContext();
            return context == 0L ? null : new Stamp(device, context);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null; // Unknown wrappers/backends retain independently owned masks.
        }
    }
}
//#endif
