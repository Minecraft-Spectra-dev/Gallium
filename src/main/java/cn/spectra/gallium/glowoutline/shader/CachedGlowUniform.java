package cn.spectra.gallium.glowoutline.shader;

/** Opt-in marker; ordinary Minecraft uniforms retain their original upload behavior. */
public interface CachedGlowUniform {
    void gallium$enableUploadCache();
}
