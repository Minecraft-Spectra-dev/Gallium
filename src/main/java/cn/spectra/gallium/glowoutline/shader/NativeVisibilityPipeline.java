package cn.spectra.gallium.glowoutline.shader;

//#if MC==1_21_11 || MC==1_26_01
import cn.spectra.gallium.glowoutline.capture.OpenGlMaskOrdering;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.opengl.Uniform;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;
import java.util.*;

/** Native tagged-quad visibility uses the original shader inputs and actual depth testing. */
public final class NativeVisibilityPipeline {
    //#if MC>=1_26_00
    private static final VertexFormat ENTITY_FORMAT=DefaultVertexFormat.ENTITY;
    //#else
    //$$ private static final VertexFormat ENTITY_FORMAT=DefaultVertexFormat.NEW_ENTITY;
    //#endif

    private record Compiled(Object originalProgram,RenderPipeline pipeline,Map<String,Integer> uniformSizes,Set<String> samplers){}
    private static final IdentityHashMap<RenderPipeline,Compiled> pipelines=new IdentityHashMap<>();
    private static long sequence;
    private static GLCapabilities capabilities;
    private static boolean available;
    static {GlowResources.register(pipelines::clear);}
    private NativeVisibilityPipeline(){}
    public static boolean supported(){
        if(OpenGlMaskOrdering.observe()==null)return false;
        var caps=GL.getCapabilities();
        if(capabilities!=caps){
            capabilities=caps;
            available=caps.GL_ARB_fragment_shader_interlock && (caps.OpenGL45 || caps.GL_ARB_direct_state_access)
                    && (caps.OpenGL44 || caps.GL_ARB_clear_texture) && GL11.glGetInteger(GL43.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS)>=5
                    && GL11.glGetInteger(GL42.GL_MAX_IMAGE_UNITS)>=2;
        }
        return available;
    }
    public static Object sourceProgram(RenderPipeline source,RenderPipeline derived){
        var entry=pipelines.get(source);return entry!=null && derived!=null && entry.pipeline==derived?entry.originalProgram:null;
    }
    /** The caller must provide every active source input through the last byte read by its linked block members. */
    public static boolean matchesInputs(RenderPipeline source,RenderPipeline derived,Map<String,GpuBufferSlice> uniforms,Set<String> textures){
        var entry=pipelines.get(source);
        if(entry==null || derived==null || entry.pipeline!=derived || uniforms==null || textures==null || !textures.containsAll(entry.samplers))return false;
        for(var required:entry.uniformSizes.entrySet()){
            var value=uniforms.get(required.getKey());if(value==null || value.length()<required.getValue())return false;
        }
        return true;
    }
    public static boolean current(RenderPipeline source,RenderPipeline derived){
        var entry=pipelines.get(source);
        if(entry==null || entry.pipeline!=derived)return false;
        var actual=RenderSystem.getDevice().precompilePipeline(source);
        return actual instanceof GlRenderPipeline gl && gl.isValid() && entry.originalProgram==gl.program();
    }
    private static int requiredBlockBytes(int program,int block){
        int count=GL31.glGetActiveUniformBlocki(program,block,GL31.GL_UNIFORM_BLOCK_ACTIVE_UNIFORMS);
        int blockSize=GL31.glGetActiveUniformBlocki(program,block,GL31.GL_UNIFORM_BLOCK_DATA_SIZE);
        if(count<=0 || count>128 || blockSize<=0)return -1;
        long end=0;
        try(var stack=MemoryStack.stackPush()){
            var members=stack.mallocInt(count);GL31.glGetActiveUniformBlockiv(program,block,GL31.GL_UNIFORM_BLOCK_ACTIVE_UNIFORM_INDICES,members);
            for(int i=0;i<count;i++){
                int member=members.get(i),type=GL31.glGetActiveUniformsi(program,member,GL31.GL_UNIFORM_TYPE);
                int shape=switch(type){
                    case GL11.GL_FLOAT,GL11.GL_INT,GL11.GL_UNSIGNED_INT,GL20.GL_BOOL -> 11;
                    case GL20.GL_FLOAT_VEC2,GL20.GL_INT_VEC2,GL30.GL_UNSIGNED_INT_VEC2,GL20.GL_BOOL_VEC2 -> 12;
                    case GL20.GL_FLOAT_VEC3,GL20.GL_INT_VEC3,GL30.GL_UNSIGNED_INT_VEC3,GL20.GL_BOOL_VEC3 -> 13;
                    case GL20.GL_FLOAT_VEC4,GL20.GL_INT_VEC4,GL30.GL_UNSIGNED_INT_VEC4,GL20.GL_BOOL_VEC4 -> 14;
                    case GL20.GL_FLOAT_MAT2 -> 22;case GL20.GL_FLOAT_MAT3 -> 33;case GL20.GL_FLOAT_MAT4 -> 44;
                    case GL21.GL_FLOAT_MAT2x3 -> 23;case GL21.GL_FLOAT_MAT2x4 -> 24;
                    case GL21.GL_FLOAT_MAT3x2 -> 32;case GL21.GL_FLOAT_MAT3x4 -> 34;
                    case GL21.GL_FLOAT_MAT4x2 -> 42;case GL21.GL_FLOAT_MAT4x3 -> 43;
                    default -> 0;
                };
                if(shape==0)return -1;
                long memberEnd=UniformReadExtent.end(GL31.glGetActiveUniformsi(program,member,GL31.GL_UNIFORM_OFFSET),4,shape/10,shape%10,
                        GL31.glGetActiveUniformsi(program,member,GL31.GL_UNIFORM_SIZE),GL31.glGetActiveUniformsi(program,member,GL31.GL_UNIFORM_ARRAY_STRIDE),
                        GL31.glGetActiveUniformsi(program,member,GL31.GL_UNIFORM_MATRIX_STRIDE),GL31.glGetActiveUniformsi(program,member,GL31.GL_UNIFORM_IS_ROW_MAJOR)!=0);
                if(memberEnd<0 || memberEnd>blockSize)return -1;end=Math.max(end,memberEnd);
            }
        }
        return (int)end;
    }
    public static RenderPipeline compile(RenderPipeline source){
        if(source==null || !supported() || java.nio.ByteOrder.nativeOrder()!=java.nio.ByteOrder.LITTLE_ENDIAN
                || ENTITY_FORMAT.getVertexSize()!=36 || ENTITY_FORMAT.getOffset(VertexFormatElement.NORMAL)!=32
                || VertexFormatElement.NORMAL.byteSize()!=3)return null;
        if(pipelines.size()>=128 && !pipelines.containsKey(source))pipelines.clear();
        Float alphaMinimum=NativeMaskAlphaThreshold.minimum();if(alphaMinimum==null)return null;
        var device=RenderSystem.getDevice();
        if(!(device.precompilePipeline(source) instanceof GlRenderPipeline original))return null;
        var cached=pipelines.get(source);if(cached!=null && cached.originalProgram==original.program())return cached.pipeline;
        // A rejected source is retried only when the engine creates a new program.
        pipelines.put(source,new Compiled(original.program(),null,Map.of(),Set.of()));
        //#if MC>=1_26_00
        var depth=source.getDepthStencilState();
        if(!original.isValid() || source.getVertexFormat()!=ENTITY_FORMAT || source.getVertexFormatMode()!=VertexFormat.Mode.QUADS
                || source.getColorTargetState().writeMask()!=15 || source.getColorTargetState().blendFunction().isPresent()
                || depth==null || !depth.writeDepth() || !depth.depthTest().name().equals("LESS_THAN_OR_EQUAL")
                || depth.depthBiasScaleFactor()!=0 || depth.depthBiasConstant()!=0)return null;
        //#else
        //$$ if(!original.isValid() || source.getVertexFormat()!=ENTITY_FORMAT || source.getVertexFormatMode()!=VertexFormat.Mode.QUADS
        //$$         || !source.isWriteColor() || !source.isWriteAlpha()
        //$$         || source.getColorLogic()!=com.mojang.blaze3d.platform.LogicOp.NONE
        //$$         || !source.isWriteDepth() || source.getDepthTestFunction()!=com.mojang.blaze3d.platform.DepthTestFunction.LEQUAL_DEPTH_TEST
        //$$         || source.getDepthBiasScaleFactor()!=0 || source.getDepthBiasConstant()!=0)return null;
        //#endif
        int program=original.program().getProgramId();String vertex=null,fragment=null;
        try(var stack=MemoryStack.stackPush()){
            if(GL20.glGetProgrami(program,GL20.GL_ATTACHED_SHADERS)!=2)return null;
            var shaders=stack.mallocInt(2);var count=stack.mallocInt(1);GL20.glGetAttachedShaders(program,count,shaders);
            for(int i=0;i<2;i++){int shader=shaders.get(i),type=GL20.glGetShaderi(shader,GL20.GL_SHADER_TYPE);
                if(type==GL20.GL_VERTEX_SHADER)vertex=GL20.glGetShaderSource(shader);else if(type==GL20.GL_FRAGMENT_SHADER)fragment=GL20.glGetShaderSource(shader);}
        }
        if(vertex==null || fragment==null || !NativeShaderSideEffects.hasOnlyRasterOutputs(vertex) || !NativeShaderSideEffects.hasOnlyRasterOutputs(fragment)
                || !VertexPositionProof.matchesUniformBlocks(vertex) || !VertexPositionProof.supportsBaseVertex(vertex) || vertex.contains("gl_VertexID") || vertex.contains("gl_InstanceID")
                || fragment.contains("gl_FragDepth") || fragment.contains("gl_SampleMask") || fragment.contains("gl_PrimitiveID") || !fragment.matches("(?s).*\\bout\\s+vec4\\s+fragColor\\s*;.*"))return null;
        //#if MC==1_21_11
        //$$ if(source.getBlendFunction().isPresent()){
        //$$     var blend=source.getBlendFunction().get();
        //$$     if(blend.sourceAlpha()!=com.mojang.blaze3d.platform.SourceFactor.ONE
        //$$             || blend.destAlpha()!=com.mojang.blaze3d.platform.DestFactor.ONE_MINUS_SRC_ALPHA
        //$$             || GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA)!=GL14.GL_FUNC_ADD
        //$$             || !NativeBlendVisibilitySource.verified(fragment))return null;
        //$$ }
        //#endif
        var uniformSizes=new HashMap<String,Integer>();var samplers=new HashSet<String>();
        for(var entry:original.program().getUniforms().entrySet()){
            if(entry.getValue() instanceof Uniform.Ubo){
                int block=GL31.glGetUniformBlockIndex(program,entry.getKey());if(block==GL31.GL_INVALID_INDEX)return null;
                int size=requiredBlockBytes(program,block);if(size<=0)return null;
                uniformSizes.put(entry.getKey(),size);
            }else if(entry.getValue() instanceof Uniform.Sampler)samplers.add(entry.getKey());
            else return null;
        }
        if(uniformSizes.size()>32)return null;
        vertex=vertex.replaceFirst("#version[^\\r\\n]*","#version 450").replaceFirst("\\bvoid\\s+main\\s*\\(\\s*\\)","void galliumPrimaryVertex()");
        vertex+="""
            layout(std430,binding=4) readonly buffer GalliumPrimaryVertices { uint galliumPrimaryWords[]; };
            flat out uint galliumPrimaryId;
            void main(){galliumPrimaryId=galliumPrimaryWords[uint(gl_VertexID)*9u+8u]>>24u;galliumPrimaryVertex();}
            """;
        fragment=fragment.replaceFirst("#version[^\\r\\n]*","#version 450\n#extension GL_ARB_fragment_shader_interlock : require")
                .replaceFirst("\\bvoid\\s+main\\s*\\(\\s*\\)","void galliumPrimaryFragment()");
        fragment+="""
            layout(early_fragment_tests) in;
            layout(pixel_interlock_ordered) in;
            layout(rg32ui,binding=1) coherent uniform uimage2D GalliumPrimaryVisibility;
            flat in uint galliumPrimaryId;
            void main(){
                galliumPrimaryFragment();
                beginInvocationInterlockARB();
                if(galliumPrimaryId!=0u){
                    uint id=galliumPrimaryId-1u;uvec2 bit=uvec2(0u);bit[id/32u]=1u<<(id&31u);
                    bool visible=fragColor.a>=GALLIUM_ALPHA_MIN;
                    ivec2 coord=ivec2(gl_FragCoord.xy);uvec2 old=imageLoad(GalliumPrimaryVisibility,coord).xy;
                    old=(old&~bit)|(visible?bit:uvec2(0u));
                    imageStore(GalliumPrimaryVisibility,coord,uvec4(old,0u,0u));
                }
                endInvocationInterlockARB();
            }
            """;
        fragment=fragment.replace("GALLIUM_ALPHA_MIN",Float.toString(alphaMinimum));
        var id=Identifier.fromNamespaceAndPath("gallium","internal/native_visibility/"+sequence++);
        var builder=RenderPipeline.builder().withLocation(id).withVertexShader(id).withFragmentShader(id)
                //#if MC>=1_26_00
                .withColorTargetState(new ColorTargetState(Optional.empty(),0)).withDepthStencilState(new DepthStencilState(depth.depthTest(),false,depth.depthBiasScaleFactor(),depth.depthBiasConstant()))
                //#else
                //$$ .withColorWrite(false,false).withDepthWrite(false).withDepthTestFunction(source.getDepthTestFunction())
                //#endif
                .withCull(source.isCull())
                .withPolygonMode(source.getPolygonMode()).withVertexFormat(source.getVertexFormat(),source.getVertexFormatMode());
        for(var value:source.getSamplers())builder.withSampler(value);
        for(var value:source.getUniforms()){if(value.textureFormat()==null)builder.withUniform(value.name(),value.type());else builder.withUniform(value.name(),value.type(),value.textureFormat());}
        var pipeline=builder.build();String vs=vertex,fs=fragment;
        var compiled=device.precompilePipeline(pipeline,(shader,type)->!id.equals(shader)?null:type==ShaderType.VERTEX?vs:fs);
        if(!compiled.isValid())return null;
        pipelines.put(source,new Compiled(original.program(),pipeline,Map.copyOf(uniformSizes),Set.copyOf(samplers)));return pipeline;
    }
}

//#else
//$$ public final class NativeVisibilityPipeline {}
//#endif
