package cn.spectra.gallium.glowoutline.shader;

//#if MC>=1_21_06 && MC<1_26_02
import cn.spectra.gallium.Gallium;
import cn.spectra.gallium.glowoutline.capture.OpenGlMaskOrdering;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.opengl.GlStateManager;
//#if MC<1_26_01
//$$ import com.mojang.blaze3d.platform.DepthTestFunction;
//#endif
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MappableRingBuffer;
//#if MC>=1_21_09
import net.minecraft.resources.Identifier;
//#else
//$$ import net.minecraft.resources.ResourceLocation;
//#endif
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import java.nio.ByteBuffer;
import java.util.*;

/** Ordered instances retain each original full-screen triangle and its own scissor. */
public final class NativeGlowInstances implements AutoCloseable {
    private static final int LIMIT = 16, CAPACITY = 2 * 1024 * 1024;
    private static final Map<String, Optional<Pipeline>> pipelines = new HashMap<>();
    private static final Map<String, Optional<Pipeline>> identityPipelines = new HashMap<>();
    private static final Map<String, Optional<Pipeline>> visibilityPipelines = new HashMap<>();
    private static final Map<String, Object> sourcePrograms = new HashMap<>();
    private static MappableRingBuffer uniforms;
    //#if MC<1_26_01
    //$$ private static GpuBuffer emptyVertices;
    //#endif
    private static NativeGlowInstances current;
    private final OpenGlMaskOrdering.Stamp ordering = OpenGlMaskOrdering.observe();
    private final NativeGlowInstances parent = current;
    private final float[] oldViewports = new float[(LIMIT - 1) * 4];
    private GpuBuffer buffer;
    private int cursor;
    private boolean savedViewports, applied;
    private Group drawing;
    record Pipeline(RenderPipeline info, int program, int stride, int alignmentOffset, int offsetOffset,
                    Object programIdentity, boolean visibilityDiffuseIndependent) {}
    private record Group(Pipeline pipeline, BoundedGlowContract.Rectangle[] rectangles) {}

    static {
        GlowResources.registerPipeline(() -> { pipelines.clear(); identityPipelines.clear(); visibilityPipelines.clear(); sourcePrograms.clear(); });
        GlowResources.register(() -> { if (uniforms != null) uniforms.close(); uniforms = null; current = null;
            //#if MC<1_26_01
            //$$ if(emptyVertices!=null)emptyVertices.close();emptyVertices=null;
            //#endif
        });
    }

    NativeGlowInstances() { current = this; }

    int draw(List<NativeMaskAtlas.Entry> entries, int start, RenderTarget output, CommandEncoder encoder,
             GpuTextureView diffuse, GpuTextureView scene, GpuTextureView foreground, GlowUniformBuffer values) {
        if (parent != null || ordering == null || !ordering.current() || current != this) return 0;
        var caps = GL.getCapabilities();
        if ((!caps.OpenGL41 && !caps.GL_ARB_viewport_array) || !caps.GL_ARB_shader_viewport_layer_array
                || GL11.glGetInteger(GL41.GL_MAX_VIEWPORTS) < LIMIT) return 0;
        var first = entries.get(start);
        boolean visibility=first.visibility()!=null;
        if (visibility && !first.visibility().valid()) return 0;
        var contract = BoundedGlowContracts.get(first.state().config.shader());
        if (contract == null || !contract.instanceUniforms() || !contract.atlasStorage()) return 0;
        int count = 1;
        while (count < LIMIT && start + count < entries.size()) {
            var entry = entries.get(start + count);
            if (!sameGroup(first,entry)) break;
            count++;
        }
        if (count < 2 && !visibility) return 0;
        var rectangles = new BoundedGlowContract.Rectangle[count];
        for (int i = 0; i < count; i++) {
            var entry = entries.get(start + i); var state = entry.state();
            if (!state.hasOrdinaryMaskStored(entry.epoch(), entry) || !state.maskPreparedThisFrame
                    || state.compositedThisFrame || !state.hasPayloadReplayAttempted()
                    || state.streamingReplayPlan() != null) return 0;
            //#if MC==1_21_08 || MC==1_21_10 || MC==1_26_01
            rectangles[i] = cn.spectra.gallium.glowoutline.IrisCompat.isShaderActive()
                    ? contract.mappedRectangle(state.maskBounds, output.width, output.height,
                        state.itemWorldToUv.x, state.itemWorldToUv.y, state.lastMaskScaleX,
                        state.lastMaskScaleY, state.lastMaskOffsetX, state.lastMaskOffsetY)
                    : contract.rectangle(state.maskBounds, output.width, output.height,
                        state.itemWorldToUv.x, state.itemWorldToUv.y);
            //#elseif MC==1_21_11
            //$$ var observed=OutlineTemporalStabilizer.capturingSr()?OutlineSrCapture.bounds(state):state.maskBounds;
            //$$ rectangles[i]=observed==null?null:cn.spectra.gallium.glowoutline.IrisCompat.isShaderActive()
            //$$         ?contract.mappedRectangle(observed,output.width,output.height,state.itemWorldToUv.x,state.itemWorldToUv.y,
            //$$             state.lastMaskScaleX,state.lastMaskScaleY,state.lastMaskOffsetX,state.lastMaskOffsetY)
            //$$         :contract.rectangle(observed,output.width,output.height,state.itemWorldToUv.x,state.itemWorldToUv.y);
            //#else
            //$$ rectangles[i] = contract.rectangle(state.maskBounds, output.width, output.height,
            //$$         state.itemWorldToUv.x, state.itemWorldToUv.y);
            //#endif
            if (rectangles[i] == null) return 0;
        }
        String shaderName = first.state().config.shader();
        if (!visibility && !sourceCurrent(shaderName)) return 0;
        var pipeline = visibility ? first.visibility().pipeline()
                : pipelines.computeIfAbsent(shaderName, name -> compile(name, false)).orElse(null);
        if (pipeline == null) return 0;
        var sources = new ByteBuffer[count];
        boolean identity = true;
        for (int i = 0; i < count; i++) {
            var entry = entries.get(start + i); var state = entry.state();
            sources[i] = values.batchValue(entry.slot(), pipeline.stride);
            if (sources[i] == null) return 0;
            identity &= state.exactDepthAlignment
                    && state.lastMaskScaleX == 1 && state.lastMaskScaleY == 1
                    && state.lastSceneScaleX == 1 && state.lastSceneScaleY == 1
                    && state.lastMaskOffsetX == 0 && state.lastMaskOffsetY == 0
                    && state.lastSceneOffsetX == 0 && state.lastSceneOffsetY == 0
                    && IdentityGlowSource.matchesUniforms(sources[i], pipeline.alignmentOffset, pipeline.offsetOffset);
        }
        if (identity && !visibility) {
            var specialized = identityPipelines.computeIfAbsent(shaderName, name -> compile(name, true)).orElse(null);
            if (specialized != null && specialized.stride == pipeline.stride) pipeline = specialized;
        }
        int size = pipeline.stride * LIMIT;
        int alignment = RenderSystem.getDevice().getUniformOffsetAlignment();
        int offset = ((cursor + alignment - 1) / alignment) * alignment;
        if ((long) offset + size > CAPACITY) return 0;
        if (ModernSparseGlow.begin(first.state(), output, foreground) == null) return 0;
        if (uniforms == null) uniforms = new MappableRingBuffer(() -> "Glow instance uniforms",
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, CAPACITY);
        if (buffer == null) buffer = uniforms.currentBuffer();
        var slice = buffer.slice(offset, size);
        try (var mapped = encoder.mapBuffer(slice, false, true)) {
            for (int i = 0; i < count; i++) {
                var source = sources[i];
                mapped.data().put(i * pipeline.stride, source, source.position(), source.remaining());
            }
        }
        cursor = offset + size;
        // First use can allocate and clear this fallback, so resolve it before opening a pass.
        var baseDepth = first.baseDepth() == null ? ForegroundOcclusion.farTarget(encoder).getColorTextureView()
                : first.baseDepth().getDepthTextureView();
        saveViewports();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var viewport = stack.mallocFloat((LIMIT - 1) * 4);
            for (int i = 1; i < LIMIT; i++) viewport.put(0).put(0).put(output.width).put(output.height);
            viewport.flip(); GL41.glViewportArrayv(1, viewport);
        }
        var indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.TRIANGLES);
        var indexBuffer = indices.getBuffer(3);
        //#if MC<1_26_01
        //$$ if(emptyVertices==null)emptyVertices=RenderSystem.getDevice().createBuffer(()->"Glow empty vertex binding",GpuBuffer.USAGE_VERTEX,16);
        //#endif
        var firstRectangle = rectangles[0];
        try (var imageBinding = visibility ? first.visibility().bind() : null;
             var pass = encoder.createRenderPass(() -> "Glow instances", output.getColorTextureView(), OptionalInt.empty())) {
            pass.setPipeline(pipeline.info);
            pass.enableScissor(firstRectangle.x(), firstRectangle.y(), firstRectangle.width(), firstRectangle.height());
            pass.setUniform("GlowUniforms", slice);
            SamplerHelper.bindClampToEdge(pass, WorldGlowShader.FOREGROUND_SAMPLER, foreground, FilterMode.NEAREST);
            SamplerHelper.bindClampToEdge(pass, "DiffuseSampler", diffuse, FilterMode.LINEAR);
            SamplerHelper.bindClampToEdge(pass, "MaskSampler", first.atlas().getColorTextureView(), FilterMode.NEAREST);
            SamplerHelper.bindClampToEdge(pass, "MaskDepthSampler", first.atlas().getDepthTextureView(), FilterMode.NEAREST);
            SamplerHelper.bindClampToEdge(pass, "SceneDepthSampler", scene, FilterMode.NEAREST);
            SamplerHelper.bindClampToEdge(pass, "GalliumMaskBaseDepthSampler",
                    baseDepth, FilterMode.NEAREST);
            //#if MC<1_26_01
            //$$ pass.setVertexBuffer(0,emptyVertices);
            //#endif
            pass.setIndexBuffer(indexBuffer, indices.type());
            applied = false; drawing = new Group(pipeline, rectangles);
            try {
                pass.drawIndexed(0, 0, 3, count);
                if (!applied) throw new IllegalStateException("Glow instance scissor hook did not execute");
            } finally {
                drawing = null;
                // Native setup used this rectangle for every viewport. Restore that state
                // before returning to the encoder, including its cached scissor enable.
                GL11.glScissor(firstRectangle.x(), firstRectangle.y(), firstRectangle.width(), firstRectangle.height());
            }
        }
        ModernSparseGlow.completed(output, count);
        for (int i = 0; i < count; i++) entries.get(start + i).state().compositedThisFrame = true;
        return count;
    }

    /** Called after native program/sampler/uniform/scissor setup and before the actual draw. */
    public static void beforeNativeDraw() {
        var scope = current;
        if (scope == null || scope.drawing == null) return;
        var group = scope.drawing;
        if (scope.applied || GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) != group.pipeline.program)
            throw new IllegalStateException("Unexpected draw in glow instance scope");
        // The native encoder manages one scissor enable for all viewports.
        GlStateManager._disableScissorTest(); GlStateManager._enableScissorTest();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var boxes = stack.mallocInt(group.rectangles.length * 4);
            for (var r : group.rectangles) boxes.put(r.x()).put(r.y()).put(r.width()).put(r.height());
            boxes.flip(); GL41.glScissorArrayv(0, boxes);
        }
        scope.applied = true;
    }

    private void saveViewports() {
        if (savedViewports) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var values = stack.mallocFloat(4);
            for (int i = 1; i < LIMIT; i++) {
                GL41.glGetFloati_v(GL11.GL_VIEWPORT, i, values);
                for (int j = 0; j < 4; j++) oldViewports[(i - 1) * 4 + j] = values.get(j);
            }
        }
        savedViewports = true;
    }

    @Override public void close() {
        try {
            if (savedViewports) GL41.glViewportArrayv(1, oldViewports);
        } finally {
            try { if (buffer != null) uniforms.rotate(); }
            finally { drawing = null; if (current == this) current = parent; }
        }
    }

    private static boolean sameGroup(NativeMaskAtlas.Entry first,NativeMaskAtlas.Entry entry) {
        return entry.atlas()==first.atlas() && entry.baseDepth()==first.baseDepth()
                && (entry.visibility()==null)==(first.visibility()==null)
                && (entry.visibility()==null || entry.visibility().group()==first.visibility().group() && entry.visibility().valid())
                && entry.state().firstPerson==first.state().firstPerson
                && entry.state().config.shader().equals(first.state().config.shader());
    }

    //#if MC==1_21_11 || MC==1_26_01
    static Pipeline visibilityPlan(String name) {
        var caps=GL.getCapabilities();
        if ((!caps.OpenGL41 && !caps.GL_ARB_viewport_array) || !caps.GL_ARB_shader_viewport_layer_array
                || GL11.glGetInteger(GL41.GL_MAX_VIEWPORTS)<LIMIT || !NativeVisibilityPipeline.supported()
                || !sourceCurrent(name)) return null;
        var contract=BoundedGlowContracts.get(name);
        if(contract==null || !contract.alphaDepthOnly() || !contract.atlasStorage() || !contract.instanceUniforms())return null;
        var result=visibilityPipelines.computeIfAbsent(name,key->compile(key,true,true)).orElse(null);
        if(result!=null && uniforms==null) {
            try { uniforms=new MappableRingBuffer(()->"Glow instance uniforms",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_MAP_WRITE,CAPACITY); }
            catch(RuntimeException unavailable){return null;}
        }
        return result;
    }
    //#else
    //$$ static Pipeline visibilityPlan(String name) { return null; }
    //#endif
    private static Optional<Pipeline> compile(String name, boolean identity) { return compile(name,identity,false); }

    private static Optional<Pipeline> compile(String name, boolean identity, boolean visibility) {
        try {
            var device = RenderSystem.getDevice();
            if (!(device.precompilePipeline(GlowPipeline.getOrCreate(name)) instanceof GlRenderPipeline original)
                    || !original.isValid()) return Optional.empty();
            int program = original.program().getProgramId();
            int block = GL31.glGetUniformBlockIndex(program, "GlowUniforms");
            if (block < 0) return Optional.empty();
            int stride = GL31.glGetActiveUniformBlocki(program, block, GL31.GL_UNIFORM_BLOCK_DATA_SIZE);
            if (stride <= 0 || stride > 4096 || stride % 16 != 0
                    || stride * LIMIT > GL11.glGetInteger(GL31.GL_MAX_UNIFORM_BLOCK_SIZE)) return Optional.empty();
            int alignmentOffset = vectorOffset(program, block, stride, "ShaderAlign");
            int offsetOffset = vectorOffset(program, block, stride, "ShaderOffset");
            String vertex = null, fragment = null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                if (GL20.glGetProgrami(program, GL20.GL_ATTACHED_SHADERS) != 2) return Optional.empty();
                var count = stack.mallocInt(1); var shaders = stack.mallocInt(2);
                GL20.glGetAttachedShaders(program, count, shaders);
                for (int i = 0; i < count.get(0); i++) {
                    int shader = shaders.get(i), type = GL20.glGetShaderi(shader, GL20.GL_SHADER_TYPE);
                    String source = GL20.glGetShaderSource(shader);
                    if (type == GL20.GL_VERTEX_SHADER) vertex = InstancedGlowSource.adapt(source, true);
                    else if (type == GL20.GL_FRAGMENT_SHADER) fragment = InstancedGlowSource.adapt(source, false);
                }
            }
            if (vertex == null || fragment == null) {
                Gallium.LOGGER.warn("Instanced glow source is unsupported for {} (vertex={}, fragment={})", name, vertex != null, fragment != null);
                return Optional.empty();
            }
            if (visibility) {
                fragment=VisibilityGlowSource.adapt(fragment);
                if(fragment==null || vectorOffset(program,block,stride,"GalliumMaskStorage")<0)return Optional.empty();
            }
            if (identity) {
                if (alignmentOffset < 0 || offsetOffset < 0) return Optional.empty();
                fragment = IdentityGlowSource.specialize(fragment);
                if (fragment == null) return Optional.empty();
            }
            String variant = visibility ? "visibility/" : identity ? "identity/" : "";
            //#if MC>=1_21_09
            var shader = Identifier.fromNamespaceAndPath("gallium", "internal/instanced_glow/" + variant + name);
            //#else
            //$$ var shader = cn.spectra.gallium.glowoutline.LegacyResourceIds.create("gallium", "internal/instanced_glow/" + variant + name);
            //#endif
            var info = RenderPipeline.builder().withLocation("pipeline/gallium_instances/" + variant + name)
                    .withVertexShader(shader).withFragmentShader(shader)
                    .withSampler("DiffuseSampler").withSampler("MaskSampler").withSampler("MaskDepthSampler")
                    .withSampler("SceneDepthSampler").withSampler(WorldGlowShader.FOREGROUND_SAMPLER)
                    .withSampler("GalliumMaskBaseDepthSampler").withUniform("GlowUniforms", UniformType.UNIFORM_BUFFER)
                    //#if MC>=1_26_01
                    .withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.ADDITIVE), ColorTargetState.WRITE_COLOR))
                    .withCull(false).withDepthStencilState(Optional.empty())
                    //#else
                    //$$ .withBlend(BlendFunction.ADDITIVE).withColorWrite(true,false)
                    //$$ .withCull(false).withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST).withDepthWrite(false)
                    //#endif
                    .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES).build();
            String vs = vertex, fs = fragment;
            var compiled = device.precompilePipeline(info, (id, type) -> !shader.equals(id) ? null
                    : type == ShaderType.VERTEX ? vs : type == ShaderType.FRAGMENT ? fs : null);
            if (!(compiled instanceof GlRenderPipeline gl) || !compiled.isValid()) return Optional.empty();
            int linked = gl.program().getProgramId();
            if (visibility && !visibilityUniformsOnly(linked)) return Optional.empty();
            int linkedBlock = GL31.glGetUniformBlockIndex(linked, "GlowUniforms");
            if (linkedBlock < 0 || GL31.glGetActiveUniformBlocki(linked, linkedBlock, GL31.GL_UNIFORM_BLOCK_DATA_SIZE) != stride * LIMIT)
                return Optional.empty();
            Gallium.LOGGER.info("Created instanced glow pipeline: {}{} ({} bytes per item)", variant, name, stride);
            return Optional.of(new Pipeline(info, linked, stride, alignmentOffset, offsetOffset,
                    gl.program(), visibility && GL20.glGetUniformLocation(linked,"DiffuseSampler")<0));
        } catch (RuntimeException | LinkageError unavailable) {
            Gallium.LOGGER.warn("Instanced glow unavailable for {}: {}", name, unavailable.toString());
            return Optional.empty();
        }
    }

    /** Reject opaque aliases or externally supplied inputs that could inspect the borrowed mask. */
    private static boolean visibilityUniformsOnly(int program) {
        if (GL43.glGetProgramInterfacei(program,GL43.GL_SHADER_STORAGE_BLOCK,GL43.GL_ACTIVE_RESOURCES)!=0) return false;
        var textures=Set.of("DiffuseSampler","MaskSampler","MaskDepthSampler","SceneDepthSampler",
                WorldGlowShader.FOREGROUND_SAMPLER,"GalliumMaskBaseDepthSampler");
        try (var stack=MemoryStack.stackPush()) {
            var size=stack.mallocInt(1);var type=stack.mallocInt(1);
            int count=GL20.glGetProgrami(program,GL20.GL_ACTIVE_UNIFORMS);
            for (int index=0;index<count;index++) {
                String name=GL20.glGetActiveUniform(program,index,size,type);
                int block=GL31.glGetActiveUniformsi(program,index,GL31.GL_UNIFORM_BLOCK_INDEX);
                if (block>=0) {
                    if (!GL31.glGetActiveUniformBlockName(program,block).equals("GlowUniforms")) return false;
                } else if (size.get(0)!=1 || !(type.get(0)==GL20.GL_SAMPLER_2D && textures.contains(name)
                        || type.get(0)==GL42.GL_UNSIGNED_INT_IMAGE_2D && name.equals("GalliumVisibilityImage"))) return false;
            }
        }
        return true;
    }

    /** Both cached variants and their original must ignore the pre-glow scene color. */
    static boolean diffuseIndependent(String name) {
        var compiled = RenderSystem.getDevice().precompilePipeline(GlowPipeline.getOrCreate(name));
        if (!(compiled instanceof GlRenderPipeline gl) || sourcePrograms.get(name) != gl.program()
                || !NativeDiffuseInputs.independent(gl)) return false;
        // A cold or reloaded variant must first go through compile's inline-source callback.
        // Copying for that frame avoids asking the engine to compile a missing resource file.
        return diffuseIndependent(pipelines.get(name)) && diffuseIndependent(identityPipelines.get(name))
                && (!visibilityPipelines.containsKey(name) || visibilityDiffuseIndependent(visibilityPipelines.get(name)));
    }

    /** Only compiler-validated consumers bind the separately owned RG32UI image at draw time. */
    private static boolean visibilityDiffuseIndependent(Optional<Pipeline> cached) {
        if (cached==null) return false;
        if (cached.isEmpty()) return true;
        var expected=cached.get();
        if (!expected.visibilityDiffuseIndependent) return false;
        var compiled=RenderSystem.getDevice().precompilePipeline(expected.info);
        return compiled instanceof GlRenderPipeline gl && gl.isValid() && gl.program()==expected.programIdentity;
    }

    private static boolean diffuseIndependent(Optional<Pipeline> cached) {
        if (cached == null) return false;
        if (cached.isEmpty()) return true;
        var compiled = RenderSystem.getDevice().precompilePipeline(cached.get().info);
        return compiled instanceof GlRenderPipeline gl && NativeDiffuseInputs.independent(gl);
    }

    /** The engine may rebuild a program without changing the pack's shader name. */
    private static boolean sourceCurrent(String name) {
        var compiled = RenderSystem.getDevice().precompilePipeline(GlowPipeline.getOrCreate(name));
        if (!(compiled instanceof GlRenderPipeline gl) || !gl.isValid()) return false;
        if (sourcePrograms.put(name, gl.program()) != gl.program()) {
            pipelines.remove(name);
            identityPipelines.remove(name);
            visibilityPipelines.remove(name);
        }
        return true;
    }

    private static int vectorOffset(int program, int block, int stride, String name) {
        int index = GL31.glGetUniformIndices(program, name);
        if (index < 0 || GL31.glGetActiveUniformsi(program, index, GL31.GL_UNIFORM_BLOCK_INDEX) != block
                || GL31.glGetActiveUniformsi(program, index, GL31.GL_UNIFORM_TYPE) != GL20.GL_FLOAT_VEC4
                || GL31.glGetActiveUniformsi(program, index, GL31.GL_UNIFORM_SIZE) != 1
                || GL31.glGetActiveUniformsi(program, index, GL31.GL_UNIFORM_ARRAY_STRIDE) != 0) return -1;
        int offset = GL31.glGetActiveUniformsi(program, index, GL31.GL_UNIFORM_OFFSET);
        return offset >= 0 && (long) offset + 16 <= stride ? offset : -1;
    }
}
//#else
//$$ public final class NativeGlowInstances { private NativeGlowInstances() {} }
//#endif
