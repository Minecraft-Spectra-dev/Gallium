package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.ShaderParam;

/** Admission rules for preserving the unfiltered output when history cannot be used safely. */
public final class OutlineHistoryPolicy {
    private OutlineHistoryPolicy() {}

    /** Color/depth history, packed previous mask occupancy, and its compact reduction buffer. */
    public static long storageBytes(int width, int height) {
        if (width <= 0 || height <= 0) return Long.MAX_VALUE;
        try {
            long pixels = Math.multiplyExact(Math.multiplyExact((long) width, height), 32L);
            long atlasWidth = width <= 1 ? 1 : Long.highestOneBit(width - 1L) << 1;
            long atlasHeight = height <= 1 ? 1 : Long.highestOneBit(height - 1L) << 1;
            long occupancy = Math.multiplyExact(Math.multiplyExact((atlasWidth + 31) / 32, atlasHeight), 4L);
            return Math.addExact(Math.addExact(pixels, occupancy), 2L * 128L * 80L);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    /** SR display replay retains its native alpha and depth regions until the final history pass. */
    public static long srStorageBytes(int width,int height) {
        if(width<=0 || height<=0)return Long.MAX_VALUE;
        try {
            long w=width<=1?1:Long.highestOneBit(width-1L)<<1;
            long h=height<=1?1:Long.highestOneBit(height-1L)<<1;
            return Math.addExact(storageBytes(width,height),Math.multiplyExact(Math.multiplyExact(w,h),8L));
        } catch(ArithmeticException overflow) { return Long.MAX_VALUE; }
    }

    /** Negative additive effects cannot be reconstructed from a nonnegative color difference. */
    public static boolean additiveParameters(ItemEffectConfig config) {
        if (!OriginalGlowParameters.supported(config)) return false;
        for (var parameter : config.params()) {
            if (parameter instanceof ShaderParam.Float value
                    && value.name().equals("Intensity") && value.value() < 0) return false;
            if (parameter instanceof ShaderParam.Vec3 value
                    && (value.x() < 0 || value.y() < 0 || value.z() < 0)) return false;
        }
        return true;
    }
}
