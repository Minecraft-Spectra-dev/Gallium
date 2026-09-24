package cn.spectra.gallium.glowoutline.shader;

//#if MC<1_21_05
//$$ import cn.spectra.gallium.Gallium;
//$$ import cn.spectra.gallium.glowoutline.IrisCompat;
//$$ import cn.spectra.gallium.glowoutline.capture.GlowCaptureState;
//$$ import com.mojang.blaze3d.pipeline.RenderTarget;
//$$ import com.mojang.blaze3d.platform.GlStateManager;
//$$ import com.mojang.blaze3d.systems.RenderSystem;
//$$ import org.lwjgl.opengl.*;
//$$ import org.lwjgl.system.MemoryStack;
//$$
//$$ /** Bounded RGB draws plus coalesced, exact constant-alpha writes for opted-in shaders. */
//$$ final class LegacySparseGlow implements AutoCloseable {
//$$     private static LegacySparseGlow current;
//$$     private static int alphaProgram, alphaVao, alphaLocation, targetSizeLocation;
//$$     private static boolean unavailable;
//$$     static { GlowResources.register(LegacySparseGlow::dispose); }
//$$     private final RenderTarget output;
//$$     private final LegacySparseGlow parent;
//$$     private boolean pending;
//$$     private int foreground;
//$$     private float alpha;
//$$     private BoundedGlowContract candidate;
//$$     private final int[] oldScissor = new int[4];
//$$
//$$     LegacySparseGlow(RenderTarget output) {
//$$         this.output = output; parent = current; current = this;
//$$     }
//$$
//$$     static boolean prepareStored(RenderTarget target,int foregroundTexture,BoundedGlowContract contract) {
//$$         var frame=current;if(frame==null || frame.output!=target || contract==null || !ensureProgram())return false;
//$$         if(frame.pending && (frame.foreground!=foregroundTexture || frame.alpha!=contract.constantAlpha()))frame.flush();
//$$         frame.foreground=foregroundTexture;frame.candidate=contract;return true;
//$$     }
//$$     static void storedCompleted(RenderTarget target) {
//$$         var frame=current;if(frame==null || frame.output!=target || frame.candidate==null)throw new IllegalStateException("Missing stored alpha scope");
//$$         frame.pending=true;frame.alpha=frame.candidate.constantAlpha();frame.candidate=null;
//$$     }
//$$
//$$     static boolean begin(GlowCaptureState state, RenderTarget target, int foregroundTexture) {
//$$         var frame = current;
//$$         if (frame == null || frame.output != target) return false;
//$$         frame.candidate = null;
//$$         boolean iris = IrisCompat.isShaderActive();
//$$         BoundedGlowContract contract = state.guiEntity == null && !state.superResolutionPrepared
//$$                 && target.viewWidth == target.width && target.viewHeight == target.height
//$$                 && ordinaryRaster() ? BoundedGlowContracts.get(state.config.shader()) : null;
//$$         boolean mapped = iris && contract != null && !IrisCompat.isActiveSrRuntime()
//$$                 && BoundedGlowContracts.automatic(state.config.shader())
//$$                 && LegacyGlowInstances.parameters(state.config);
//$$         if (!mapped && (iris || state.lastMaskScaleX != 1 || state.lastMaskScaleY != 1
//$$                 || state.lastMaskOffsetX != 0 || state.lastMaskOffsetY != 0)) contract = null;
//$$         var rectangle = contract == null ? null : mapped
//$$                 ? contract.mappedRectangle(state.maskBounds, target.width, target.height,
//$$                     state.itemWorldToUv.x, state.itemWorldToUv.y, state.lastMaskScaleX,
//$$                     state.lastMaskScaleY, state.lastMaskOffsetX, state.lastMaskOffsetY)
//$$                 : contract.rectangle(state.maskBounds, target.width, target.height,
//$$                     state.itemWorldToUv.x, state.itemWorldToUv.y);
//$$         if (rectangle == null || !ensureProgram()) { frame.flush(); return false; }
//$$         if (frame.pending && (frame.foreground != foregroundTexture || frame.alpha != contract.constantAlpha())) frame.flush();
//$$         frame.foreground = foregroundTexture;
//$$         frame.candidate = contract;
//$$         GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, frame.oldScissor);
//$$         RenderSystem.enableScissor(rectangle.x(), rectangle.y(), rectangle.width(), rectangle.height());
//$$         return true;
//$$     }
//$$
//$$     private static boolean ordinaryRaster() {
//$$         if (GL11.glIsEnabled(GL11.GL_SCISSOR_TEST) || GL11.glIsEnabled(GL11.GL_STENCIL_TEST)
//$$                 || GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB) != GL14.GL_FUNC_ADD
//$$                 || GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA) != GL14.GL_FUNC_ADD) return false;
//$$         try (var stack = MemoryStack.stackPush()) {
//$$             var channels = stack.mallocInt(4); GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, channels);
//$$             return channels.get(0) != 0 && channels.get(1) != 0
//$$                     && channels.get(2) != 0 && channels.get(3) != 0;
//$$         }
//$$     }
//$$
//$$     static void end(RenderTarget target, boolean completed) {
//$$         var frame = current;
//$$         if (frame == null || frame.output != target || frame.candidate == null) return;
//$$         RenderSystem.disableScissor();
//$$         GlStateManager._scissorBox(frame.oldScissor[0], frame.oldScissor[1], frame.oldScissor[2], frame.oldScissor[3]);
//$$         if (completed) { frame.pending = true; frame.alpha = frame.candidate.constantAlpha(); }
//$$         frame.candidate = null;
//$$     }
//$$
//$$     private void flush() {
//$$         if (!pending) return;
//$$         pending = false;
//$$         try (MemoryStack stack = MemoryStack.stackPush()) {
//$$             int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
//$$             int write = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
//$$             int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
//$$             int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
//$$             int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
//$$             boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
//$$             boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE), blend = GL11.glIsEnabled(GL11.GL_BLEND);
//$$             boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
//$$             var viewport = stack.mallocInt(4); GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
//$$             var color = stack.mallocInt(4); GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, color);
//$$             RenderSystem.activeTexture(GL13.GL_TEXTURE0);
//$$             int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
//$$             int sampler = GL.getCapabilities().OpenGL33 ? GL11.glGetInteger(GL33.GL_SAMPLER_BINDING) : -1;
//$$             try {
//$$                 output.bindWrite(true);
//$$                 RenderSystem.disableDepthTest(); RenderSystem.depthMask(false);
//$$                 RenderSystem.disableCull(); RenderSystem.disableBlend(); RenderSystem.disableScissor();
//$$                 RenderSystem.colorMask(false, false, false, true);
//$$                 RenderSystem.bindTexture(foreground);
//$$                 if (sampler >= 0) GL33.glBindSampler(0, 0);
//$$                 GlStateManager._glUseProgram(alphaProgram);
//$$                 GL20.glUniform1f(alphaLocation, alpha);
//$$                 GL20.glUniform2i(targetSizeLocation, output.width, output.height);
//$$                 GL30.glBindVertexArray(alphaVao);
//$$                 GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
//$$             } finally {
//$$                 GL30.glBindVertexArray(vao); GlStateManager._glUseProgram(program);
//$$                 RenderSystem.bindTexture(texture);
//$$                 if (sampler >= 0) GL33.glBindSampler(0, sampler);
//$$                 RenderSystem.activeTexture(activeTexture);
//$$                 RenderSystem.colorMask(color.get(0) != 0, color.get(1) != 0, color.get(2) != 0, color.get(3) != 0);
//$$                 if (depthTest) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
//$$                 RenderSystem.depthMask(depthWrite);
//$$                 if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
//$$                 if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
//$$                 if (scissor) GlStateManager._enableScissorTest(); else RenderSystem.disableScissor();
//$$                 GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
//$$                 GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, write);
//$$                 RenderSystem.viewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
//$$             }
//$$         }
//$$     }
//$$
//$$     @Override public void close() {
//$$         try { flush(); } finally { if (current == this) current = parent; }
//$$     }
//$$
//$$     private static boolean ensureProgram() {
//$$         if (unavailable) return false;
//$$         if (alphaProgram != 0) return true;
//$$         int vertex = 0, fragment = 0, program = 0;
//$$         try {
//$$             vertex = compile(GL20.GL_VERTEX_SHADER, """
//$$                     #version 150
//$$                     void main() {
//$$                         vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
//$$                         gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
//$$                     }
//$$                     """);
//$$             fragment = compile(GL20.GL_FRAGMENT_SHADER, """
//$$                     #version 150
//$$                     uniform sampler2D Foreground;
//$$                     uniform ivec2 TargetSize;
//$$                     uniform float Alpha;
//$$                     out vec4 Color;
//$$                     void main() {
//$$                         ivec2 size = textureSize(Foreground, 0);
//$$                         ivec2 pixel = clamp(ivec2(floor(gl_FragCoord.xy / vec2(TargetSize) * vec2(size))),
//$$                                 ivec2(0), size - ivec2(1));
//$$                         if (texelFetch(Foreground, pixel, 0).r < 1.0) discard;
//$$                         Color = vec4(0.0, 0.0, 0.0, Alpha);
//$$                     }
//$$                     """);
//$$             program = GL20.glCreateProgram(); GL20.glAttachShader(program, vertex); GL20.glAttachShader(program, fragment);
//$$             GL30.glBindFragDataLocation(program, 0, "Color"); GL20.glLinkProgram(program);
//$$             if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
//$$             alphaLocation = GL20.glGetUniformLocation(program, "Alpha");
//$$             targetSizeLocation = GL20.glGetUniformLocation(program, "TargetSize");
//$$             // New sampler uniforms default to unit zero; no program binding is required here.
//$$             alphaVao = GL30.glGenVertexArrays(); alphaProgram = program; program = 0;
//$$             return true;
//$$         } catch (RuntimeException failure) {
//$$             unavailable = true; Gallium.LOGGER.warn("Sparse glow unavailable: {}", failure.toString());
//$$             return false;
//$$         } finally {
//$$             if (vertex != 0) GL20.glDeleteShader(vertex);
//$$             if (fragment != 0) GL20.glDeleteShader(fragment);
//$$             if (program != 0) GL20.glDeleteProgram(program);
//$$         }
//$$     }
//$$
//$$     private static int compile(int type, String source) {
//$$         int shader = GL20.glCreateShader(type);
//$$         GL20.glShaderSource(shader, source); GL20.glCompileShader(shader);
//$$         if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
//$$             String error = GL20.glGetShaderInfoLog(shader); GL20.glDeleteShader(shader);
//$$             throw new IllegalStateException(error);
//$$         }
//$$         return shader;
//$$     }
//$$
//$$     private static void dispose() {
//$$         if (alphaProgram != 0) GL20.glDeleteProgram(alphaProgram);
//$$         if (alphaVao != 0) GL30.glDeleteVertexArrays(alphaVao);
//$$         alphaProgram = alphaVao = 0; unavailable = false; current = null;
//$$     }
//$$ }
//#else
final class LegacySparseGlow { private LegacySparseGlow() {} }
//#endif
