package cn.spectra.gallium.glowoutline.shader;
//#if MC>=1_21_06 && MC<1_26_02
/** Optional frame-owned image source for an otherwise ordinary atlas entry. */
public interface MaskStorageImage {
    int id();
    boolean valid();
    Object group();
    NativeGlowInstances.Pipeline pipeline();
    Binding bind();
    interface Binding extends AutoCloseable { @Override void close(); }
}
//#else
//$$ public interface MaskStorageImage {}
//#endif
