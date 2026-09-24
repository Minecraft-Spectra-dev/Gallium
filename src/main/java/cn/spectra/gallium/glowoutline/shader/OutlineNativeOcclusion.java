package cn.spectra.gallium.glowoutline.shader;

//#if MC==1_21_11
//$$ import cn.spectra.gallium.glowoutline.ShaderParam;
//$$ import cn.spectra.gallium.glowoutline.capture.GlowCaptureManager;
//$$ import com.mojang.blaze3d.opengl.GlTexture;
//$$ import org.lwjgl.opengl.*;
//$$ import org.lwjgl.system.MemoryStack;
//$$ import java.util.List;
//$$
//$$ /** Exact inter-item occlusion before temporal resolve, using this frame's native surfaces. */
//$$ final class OutlineNativeOcclusion {
//$$     private static int fbo, vao, reduce, effect, copy, far;
//$$     static { GlowResources.register(OutlineNativeOcclusion::dispose); }
//$$     private static final String VERTEX = """
//$$             #version 450 core
//$$             out vec2 texCoord;
//$$             void main(){vec2 p=vec2((gl_VertexID&1)<<2,(gl_VertexID&2)<<1);gl_Position=vec4(p-1.0,0.0,1.0);texCoord=p*0.5;}
//$$             """;
//$$     private static final String REDUCE = """
//$$             #version 450 core
//$$             uniform sampler2D Alpha, Depth;
//$$             uniform int Count;
//$$             uniform ivec4 Regions[128];
//$$             uniform ivec2 Offsets[128];
//$$             out float Nearest;
//$$             void main(){
//$$                 ivec2 p=ivec2(gl_FragCoord.xy);float z=1.0;
//$$                 for(int i=0;i<Count;i++){
//$$                     ivec4 r=Regions[i];
//$$                     if(all(greaterThanEqual(p,r.xy))&&all(lessThan(p,r.xy+r.zw))){
//$$                         ivec2 a=p+Offsets[i];
//$$                         if(texelFetch(Alpha,a,0).a>0.01)z=min(z,texelFetch(Depth,a,0).r);
//$$                     }
//$$                 }
//$$                 Nearest=z;
//$$             }
//$$             """;
//$$     private static int shader(int type,String source) {
//$$         int id=GL20.glCreateShader(type);
//$$         try {
//$$             GL20.glShaderSource(id,source);GL20.glCompileShader(id);
//$$             if(GL20.glGetShaderi(id,GL20.GL_COMPILE_STATUS)==0)throw new IllegalStateException(GL20.glGetShaderInfoLog(id));
//$$             return id;
//$$         } catch(RuntimeException|Error failure){GL20.glDeleteShader(id);throw failure;}
//$$     }
//$$     private static int program(String source) {
//$$         int v=0,f=0,p=0;
//$$         try {
//$$             v=shader(GL20.GL_VERTEX_SHADER,VERTEX);f=shader(GL20.GL_FRAGMENT_SHADER,source);p=GL20.glCreateProgram();
//$$             GL20.glAttachShader(p,v);GL20.glAttachShader(p,f);GL20.glLinkProgram(p);
//$$             if(GL20.glGetProgrami(p,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(p));
//$$             return p;
//$$         } catch(RuntimeException|Error failure){if(p!=0)GL20.glDeleteProgram(p);throw failure;}
//$$         finally{if(v!=0)GL20.glDeleteShader(v);if(f!=0)GL20.glDeleteShader(f);}
//$$     }
//$$     private static void sampler(int p,String name,int unit,int texture) {
//$$         GL13.glActiveTexture(GL13.GL_TEXTURE0+unit);GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);GL33.glBindSampler(unit,0);
//$$         GL20.glUniform1i(GL20.glGetUniformLocation(p,name),unit);
//$$     }
//$$     private static int location(String name){return GL20.glGetUniformLocation(effect,name);}
//$$
//$$     /** Writes only the scratch color. The displayed raw image remains intact if this fails. */
//$$     static void render(int before,int output,int nativeScene,int width,int height,List<NativeMaskAtlas.Entry> entries) {
//$$         if(entries.isEmpty() || entries.size()>128)throw new IllegalStateException("Incomplete native occlusion frame");
//$$         var atlas=entries.getFirst().atlas();
//$$         int alpha=((GlTexture)atlas.getColorTexture()).glId(),depth=((GlTexture)atlas.getDepthTexture()).glId();
//$$         var world=entries.stream().filter(e->!e.state().firstPerson).toList();
//$$         try(var saved=new OutlineTemporalGlState();var stack=MemoryStack.stackPush()) {
//$$             int sr=GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),dr=GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
//$$             int sa=GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),da=GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
//$$             int er=GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB),ea=GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
//$$             try {
//$$                 if(effect==0) {
//$$                     String source=OriginalGlowSource.nativeOcclusionStage();
//$$                     if(source==null)throw new IllegalStateException("Unverified native occlusion shader");
//$$                     reduce=program(REDUCE);
//$$                     copy=program("#version 450 core\nuniform sampler2D Before;out vec4 Color;void main(){Color=texelFetch(Before,ivec2(gl_FragCoord.xy),0);}");
//$$                     effect=program(source);fbo=GL45.glCreateFramebuffers();vao=GL45.glCreateVertexArrays();
//$$                     far=GL45.glCreateTextures(GL11.GL_TEXTURE_2D);GL45.glTextureStorage2D(far,1,GL30.GL_R32F,1,1);
//$$                     GL45.glTextureParameteri(far,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST);GL45.glTextureParameteri(far,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
//$$                     GL44.glClearTexImage(far,0,GL11.GL_RED,GL11.GL_FLOAT,stack.floats(1f));
//$$                 }
//$$                 GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);GL11.glDisable(GL11.GL_DEPTH_TEST);GL11.glDisable(GL11.GL_CULL_FACE);GL11.glDepthMask(false);
//$$                 GL30.glDisablei(GL11.GL_SCISSOR_TEST,0);GL30.glDisablei(GL11.GL_BLEND,0);GL30.glColorMaski(0,true,true,true,true);
//$$                 GL30.glBindVertexArray(vao);GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER,fbo);GL11.glViewport(0,0,width,height);
//$$                 // This is the next history depth attachment. Resolve overwrites it only
//$$                 // after every glow draw below has finished reading the native union.
//$$                 GL45.glNamedFramebufferTexture(fbo,GL30.GL_COLOR_ATTACHMENT0,nativeScene,0);GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
//$$                 if(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Native occlusion FBO incomplete");
//$$                 GL20.glUseProgram(reduce);sampler(reduce,"Alpha",0,alpha);sampler(reduce,"Depth",1,depth);
//$$                 GL20.glUniform1i(GL20.glGetUniformLocation(reduce,"Count"),world.size());
//$$                 var regions=stack.mallocInt(world.size()*4);var offsets=stack.mallocInt(world.size()*2);
//$$                 for(var e:world){regions.put(e.x()).put(e.y()).put(e.width()).put(e.height());offsets.put(e.offsetX()).put(e.offsetY());}
//$$                 regions.flip();offsets.flip();GL20.glUniform4iv(GL20.glGetUniformLocation(reduce,"Regions"),regions);GL20.glUniform2iv(GL20.glGetUniformLocation(reduce,"Offsets"),offsets);
//$$                 GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
//$$
//$$                 GL45.glNamedFramebufferTexture(fbo,GL30.GL_COLOR_ATTACHMENT0,output,0);
//$$                 // Preserve the original alpha while reconstructing the additive RGB.
//$$                 GL30.glColorMaski(0,true,true,true,false);GL20.glUseProgram(copy);sampler(copy,"Before",0,before);GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
//$$                 GL20.glUseProgram(effect);GL30.glEnablei(GL11.GL_BLEND,0);GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD,GL14.GL_FUNC_ADD);GL14.glBlendFuncSeparate(GL11.GL_ONE,GL11.GL_ONE,GL11.GL_ONE,GL11.GL_ONE);
//$$                 sampler(effect,"MaskSampler",0,alpha);sampler(effect,"MaskDepthSampler",1,depth);
//$$                 sampler(effect,"SceneDepthSampler",2,((GlTexture)GlowCaptureManager.getSceneDepthTarget().getDepthTexture()).glId());
//$$                 sampler(effect,"NativeScene",3,nativeScene);sampler(effect,"GalliumMaskBaseDepthSampler",4,far);
//$$                 GL20.glUniform2f(location("ScreenSize"),width,height);GL20.glUniform1f(location("FrameTimeCounter"),GlowTime.worldSecondsFloat());
//$$                 for(var e:entries) {
//$$                     var s=e.state();
//$$                     GL20.glUniform4f(location("ShaderAlign"),s.lastMaskScaleX,s.lastSceneScaleX,s.lastMaskScaleY,s.exactDepthAlignment?s.lastSceneScaleY:-s.lastSceneScaleY);
//$$                     GL20.glUniform4f(location("ShaderOffset"),s.lastMaskOffsetX,s.lastSceneOffsetX,s.lastMaskOffsetY,s.lastSceneOffsetY);
//$$                     GL20.glUniform2f(location("GalliumWorldToUv"),s.itemWorldToUv.x,s.itemWorldToUv.y);
//$$                     GL20.glUniform4f(location("GalliumMaskRegion"),e.x(),e.y(),e.width(),e.height());
//$$                     GL20.glUniform4f(location("GalliumMaskStorage"),e.offsetX(),e.offsetY(),1,s.firstPerson?1:0);
//$$                     for(var parameter:s.config.params()) {
//$$                         if(parameter instanceof ShaderParam.Float v)GL20.glUniform1f(location(v.name()),v.value());
//$$                         else if(parameter instanceof ShaderParam.Vec3 v)GL20.glUniform3f(location(v.name()),v.x(),v.y(),v.z());
//$$                     }
//$$                     var contract=BoundedGlowContracts.get(s.config.shader());
//$$                     var rect=contract.mappedRectangle(OutlineSrCapture.bounds(s),width,height,s.itemWorldToUv.x,s.itemWorldToUv.y,s.lastMaskScaleX,s.lastMaskScaleY,s.lastMaskOffsetX,s.lastMaskOffsetY);
//$$                     if(rect==null)throw new IllegalStateException("Invalid native glow support");
//$$                     GL30.glEnablei(GL11.GL_SCISSOR_TEST,0);GL11.glScissor(rect.x(),rect.y(),rect.width(),rect.height());GL11.glDrawArrays(GL11.GL_TRIANGLES,0,3);
//$$                 }
//$$             } finally {
//$$                 GL14.glBlendFuncSeparate(sr,dr,sa,da);GL20.glBlendEquationSeparate(er,ea);
//$$                 if(fbo!=0)GL45.glNamedFramebufferTexture(fbo,GL30.GL_COLOR_ATTACHMENT0,0,0);
//$$             }
//$$         }
//$$     }
//$$     static void dispose() {
//$$         for(int p:new int[]{reduce,effect,copy})if(p!=0)GL20.glDeleteProgram(p);
//$$         if(far!=0)GL11.glDeleteTextures(far);if(fbo!=0)GL30.glDeleteFramebuffers(fbo);if(vao!=0)GL30.glDeleteVertexArrays(vao);
//$$         reduce=effect=copy=far=fbo=vao=0;
//$$     }
//$$ }
//#else
final class OutlineNativeOcclusion { private OutlineNativeOcclusion() {} }
//#endif
