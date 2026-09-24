package cn.spectra.gallium.glowoutline.capture;
import java.util.List;
/** 1.20.1 uses RenderedBuffer ownership and scalar native uploads. */
public final class LegacyMeshUploadBatch implements AutoCloseable {
    private LegacyMeshUploadBatch() {}
    public static LegacyMeshUploadBatch prepare(List<GlowCaptureState> states) { return new LegacyMeshUploadBatch(); }
    @Override public void close() {}
}
