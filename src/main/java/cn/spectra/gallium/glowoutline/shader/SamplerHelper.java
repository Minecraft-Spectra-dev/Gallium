package cn.spectra.gallium.glowoutline.shader;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
//#if MC>=1_21_06
import com.mojang.blaze3d.textures.GpuTextureView;
//#if MC>=1_21_11
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
//#else
//$$ import com.mojang.blaze3d.systems.RenderPass;
//$$ import net.minecraft.client.gui.render.TextureSetup;
//#endif
//#endif

/**
 * Cross-version sampler abstraction.
 * <p>
 * In 1.21.11 vanilla introduced {@code RenderSystem.getSamplerCache()} returning shared
 * {@link GpuSampler} instances; {@code RenderPass.bindTexture(...)} takes the sampler
 * alongside the texture view, and {@code TextureSetup.singleTexture} also takes one.
 * <p>
 * In 1.21.10 there is no {@code GpuSampler} type at all — sampler state (filter, address
 * mode) is configured directly on the {@link com.mojang.blaze3d.textures.GpuTexture} via
 * {@code setTextureFilter}/{@code setAddressMode}, and {@code RenderPass.bindSampler}
 * takes only the view. This helper hides that split: callers ask to "bind a clamp-to-edge
 * sampler with this filter to slot N", and we route to the right primitives.
 */
public final class SamplerHelper {

    private SamplerHelper() {}

    //#if MC>=1_21_06
    /**
     * Bind {@code view} to {@code samplerName} on {@code pass} as a CLAMP_TO_EDGE sampler
     * with the requested filter mode. On 1.21.10 this also mutates the underlying
     * {@code GpuTexture}'s sampler state.
     */
    public static void bindClampToEdge(RenderPass pass, String samplerName, GpuTextureView view, FilterMode filter) {
        //#if MC>=1_21_11
        pass.bindTexture(samplerName, view, RenderSystem.getSamplerCache().getClampToEdge(filter));
        //#else
        //$$ applyClampToEdge(view, filter);
        //$$ pass.bindSampler(samplerName, view);
        //#endif
    }

    /**
     * Build a {@link TextureSetup} (GUI render path) for {@code view} sampled clamp-to-edge.
     * On 1.21.11+ the sampler is encoded into the setup; on 1.21.10 we mutate the texture
     * state and emit a sampler-less setup (vanilla GUI path picks the texture's own state).
     */
    //#if MC>=1_21_11
    public static net.minecraft.client.gui.render.TextureSetup singleTextureClampToEdge(GpuTextureView view, FilterMode filter) {
        return net.minecraft.client.gui.render.TextureSetup.singleTexture(view, RenderSystem.getSamplerCache().getClampToEdge(filter));
    }
    //#else
    //$$ public static TextureSetup singleTextureClampToEdge(GpuTextureView view, FilterMode filter) {
    //$$     applyClampToEdge(view, filter);
    //$$     return TextureSetup.singleTexture(view);
    //$$ }
    //#endif

    //#if MC<1_21_11
    //$$ private static void applyClampToEdge(GpuTextureView view, FilterMode filter) {
    //$$     com.mojang.blaze3d.textures.GpuTexture tex = view.texture();
    //$$     tex.setAddressMode(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE);
    //$$     tex.setTextureFilter(filter, false);
    //$$ }
    //#endif
    //#endif
}
