package cn.spectra.gallium.glowoutline.sr;

import cn.spectra.gallium.glowoutline.sr.SrShaderPackResolver.Session;
import java.util.Optional;

/**
 * Path-independent data captured while Iris constructs a shader pack.
 *
 * <p>Iris intentionally does not retain the shader-pack {@code Path}.  Capturing the parsed SR
 * session at construction time is therefore the only reliable way to support directory packs,
 * already-mounted zip packs, and zip packs with an outer directory without reopening or guessing
 * their layout.</p>
 */
public interface SrShaderPackContext {

    boolean gallium$hasCapturedShaderPackContext();

    Optional<Session> gallium$getSrDefinitionSession();

    Optional<String> gallium$getGalliumHint();
}
