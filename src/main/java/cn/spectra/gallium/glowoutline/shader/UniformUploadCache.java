package cn.spectra.gallium.glowoutline.shader;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Bit-exact upload history for a uniform owned exclusively by a Gallium program. */
public final class UniformUploadCache {
    private final int[] uploaded;
    private int location = -1;
    private int length = -1;

    public UniformUploadCache(int capacity) {
        if (capacity < 1 || capacity > 64) throw new IllegalArgumentException("capacity");
        uploaded = new int[capacity];
    }

    public boolean matches(int currentLocation, FloatBuffer values) {
        if (currentLocation != location || length < 0 || values.limit() != length) return false;
        for (int i = 0; i < length; i++) {
            if (uploaded[i] != Float.floatToRawIntBits(values.get(i))) return false;
        }
        return true;
    }

    public boolean matches(int currentLocation, IntBuffer values) {
        if (currentLocation != location || length < 0 || values.limit() != length) return false;
        for (int i = 0; i < length; i++) if (uploaded[i] != values.get(i)) return false;
        return true;
    }

    public void uploaded(int currentLocation, FloatBuffer values) {
        if (values.limit() > uploaded.length) { invalidate(); return; }
        length = values.limit();
        location = currentLocation;
        for (int i = 0; i < length; i++) uploaded[i] = Float.floatToRawIntBits(values.get(i));
    }

    public void uploaded(int currentLocation, IntBuffer values) {
        if (values.limit() > uploaded.length) { invalidate(); return; }
        length = values.limit();
        location = currentLocation;
        for (int i = 0; i < length; i++) uploaded[i] = values.get(i);
    }

    public void invalidate() { location = -1; length = -1; }
}
