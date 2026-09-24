package cn.spectra.gallium.glowoutline.mixin.accessor;
//#if MC==1_21_11
//$$ import com.mojang.blaze3d.opengl.*;
//$$ import com.mojang.blaze3d.buffers.*;
//$$ import com.mojang.blaze3d.vertex.VertexFormat;
//$$ import java.util.HashMap;
//$$ import org.spongepowered.asm.mixin.Mixin;
//$$ import org.spongepowered.asm.mixin.gen.Accessor;
//$$ @Mixin(GlRenderPass.class)
//$$ public interface NativePrimaryPassAccessor {
//$$  @Accessor("vertexBuffers") GpuBuffer[] gallium$vertices();
//$$  @Accessor("indexBuffer") GpuBuffer gallium$index();
//$$  @Accessor("indexType") VertexFormat.IndexType gallium$indexType();
//$$  @Accessor("pipeline") GlRenderPipeline gallium$pipeline();
//$$  @Accessor("uniforms") HashMap<String,GpuBufferSlice> gallium$uniforms();
//$$  @Accessor("samplers") HashMap<String,Object> gallium$samplers();
//$$ }
//#else
public interface NativePrimaryPassAccessor {}
//#endif
