package cn.spectra.gallium.glowoutline.capture;

//#if MC<1_21_05
//$$ import cn.spectra.gallium.glowoutline.shader.GlowResources;
//$$ import cn.spectra.gallium.glowoutline.shader.VertexPositionProof;
//$$ import cn.spectra.gallium.glowoutline.mixin.accessor.LegacyCompositeRenderTypeAccessor;
//$$ import cn.spectra.gallium.glowoutline.mixin.accessor.LegacyCompositeStateAccessor;
//$$ import cn.spectra.gallium.glowoutline.mixin.accessor.LegacyTextureStateAccessor;
//$$ import com.mojang.blaze3d.systems.RenderSystem;
//$$ import com.mojang.blaze3d.vertex.MeshData;
//$$ import com.mojang.blaze3d.vertex.VertexFormat;
//$$ import com.mojang.blaze3d.vertex.VertexFormatElement;
//$$ import java.util.IdentityHashMap;
//$$ import net.minecraft.client.renderer.RenderType;
//$$ import org.lwjgl.opengl.GL20;
//$$ import org.lwjgl.opengl.GL11;
//$$ import com.mojang.blaze3d.pipeline.TextureTarget;
//$$ import cn.spectra.gallium.glowoutline.shader.NativeShaderSideEffects;
//$$ import org.lwjgl.system.MemoryStack;
//$$
//$$ /** Observes the same mesh and matrices passed to the native legacy draw. */
//$$ public final class MaskBoundsTracker {
//$$     private record Proof(int program, boolean bounded, boolean baseVertexSafe) {}
//$$     private static final IdentityHashMap<Object, Proof> proofs = new IdentityHashMap<>();
//$$     private static final IdentityHashMap<RenderType, Boolean> nativeTypes = new IdentityHashMap<>();
//$$     private static ProjectedMaskBounds current;
//$$     private static boolean baseVertexSafe;
//$$     static { GlowResources.register(() -> { current = null; proofs.clear(); nativeTypes.clear(); }); }
//$$     private MaskBoundsTracker() {}
//$$
//$$     /** Check before the caller clears the complete native mask to transparent black. */
//$$     static boolean fullColorClearKnown(TextureTarget mask) {
//$$         if (mask.viewWidth != mask.width || mask.viewHeight != mask.height
//$$                 || GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) return false;
//$$         try (var stack = MemoryStack.stackPush()) {
//$$             var channels = stack.mallocInt(4);
//$$             GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, channels);
//$$             return channels.get(0) != 0 && channels.get(1) != 0
//$$                     && channels.get(2) != 0 && channels.get(3) != 0;
//$$         }
//$$     }
//$$
//$$     public static void begin(GlowCaptureState state, int width, int height, boolean baseKnown) {
//$$         state.maskBounds.begin(width, height);
//$$         if (current != null) current.invalidate();
//$$         current = state.maskBounds;
//$$         // The caller proves a restored or fully cleared native mask; the linked vertex
//$$         // program and the exact replay matrices still have to prove every draw.
//$$         if (!baseKnown) current.invalidate();
//$$     }
//$$
//$$     public static void end() { current = null; }
//$$     static boolean canUseBaseVertex() { return baseVertexSafe; }
//$$
//$$     public static void record(MeshData mesh, RenderType type) {
//$$         baseVertexSafe = false;
//$$         if (current == null || !current.accepting()) return;
//$$         try {
//$$             if (!nativeType(type)) {
//$$                 current.invalidate(); return;
//$$             }
//$$             var state = mesh.drawState();
//$$             if (state.mode() != VertexFormat.Mode.QUADS && state.mode() != VertexFormat.Mode.TRIANGLES
//$$                     && state.mode() != VertexFormat.Mode.TRIANGLE_STRIP
//$$                     && state.mode() != VertexFormat.Mode.TRIANGLE_FAN) {
//$$                 current.invalidate(); return;
//$$             }
//$$             var format = state.format();
//$$             var shader = RenderSystem.getShader();
//$$             if (shader == null || !format.contains(VertexFormatElement.POSITION)) {
//$$                 current.invalidate(); return;
//$$             }
//#if MC>=1_21_02
//$$             int program = shader.getProgramId();
//#else
//$$             int program = shader.getId();
//#endif
//$$             Proof proof = proofs.get(shader);
//$$             if (proof == null || proof.program() != program) {
//$$                 if (proofs.size() >= 128) proofs.clear();
//$$                 proof = inspect(program);
//$$                 proofs.put(shader, proof);
//$$             }
//$$             if (!proof.bounded()) { current.invalidate(); return; }
//$$             baseVertexSafe = proof.baseVertexSafe();
//$$             current.include(mesh.vertexBuffer(), format.getVertexSize(), state.vertexCount(),
//$$                     format.getOffset(VertexFormatElement.POSITION),
//$$                     RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix());
//$$         } catch (RuntimeException | LinkageError unavailable) {
//$$             current.invalidate(); // Bounds are optional; the original draw still executes.
//$$         }
//$$     }
//$$
//$$     static boolean nativeType(RenderType type) {
//$$         if (type == null) return false;
//$$         if (nativeTypes.size() >= 512 && !nativeTypes.containsKey(type)) nativeTypes.clear();
//$$         return nativeTypes.computeIfAbsent(type, MaskBoundsTracker::isNativeType);
//$$     }
//$$
//$$     private static boolean isNativeType(RenderType type) {
//$$         if (!(type instanceof LegacyCompositeRenderTypeAccessor access)) return false;
//$$         var state = (LegacyCompositeStateAccessor) (Object) access.gallium$state();
//$$         var texture = ((LegacyTextureStateAccessor) state.gallium$texture()).gallium$cutoutTexture().orElse(null);
//$$         if (texture == null) return false;
//$$         // Identity with the memoized vanilla factories rejects a custom composite state
//$$         // that happens to reuse a standard shader but can run arbitrary setup/clear code.
//$$         return type == RenderType.armorCutoutNoCull(texture)
//$$                 || type == RenderType.entitySolid(texture) || type == RenderType.entityCutout(texture)
//$$                 || type == RenderType.entityCutoutNoCull(texture, true)
//$$                 || type == RenderType.entityCutoutNoCull(texture, false)
//$$                 || type == RenderType.entityCutoutNoCullZOffset(texture, true)
//$$                 || type == RenderType.entityCutoutNoCullZOffset(texture, false)
//$$                 || type == RenderType.itemEntityTranslucentCull(texture)
//#if MC<1_21_02
//$$                 || type == RenderType.entityTranslucentCull(texture)
//#endif
//$$                 || type == RenderType.entityTranslucent(texture, true)
//$$                 || type == RenderType.entityTranslucent(texture, false)
//$$                 || type == RenderType.entitySmoothCutout(texture)
//$$                 || type == RenderType.entityNoOutline(texture);
//$$     }
//$$
//$$     private static Proof inspect(int program) {
//$$         var unknown = new Proof(program, false, false);
//$$         if (program <= 0 || GL20.glGetProgrami(program, GL20.GL_ATTACHED_SHADERS) != 2) return unknown;
//$$         try (MemoryStack stack = MemoryStack.stackPush()) {
//$$             var count = stack.mallocInt(1);
//$$             var shaders = stack.mallocInt(2);
//$$             GL20.glGetAttachedShaders(program, count, shaders);
//$$             if (count.get(0) != 2) return unknown;
//$$             boolean vertex = false, fragment = false, baseVertexSafe = false;
//$$             for (int i = 0; i < 2; i++) {
//$$                 int shader = shaders.get(i);
//$$                 int kind = GL20.glGetShaderi(shader, GL20.GL_SHADER_TYPE);
//$$                 if (kind == GL20.GL_VERTEX_SHADER) {
//$$                     String source = GL20.glGetShaderSource(shader);
//$$                     vertex = VertexPositionProof.matches(source);
//$$                     baseVertexSafe = !source.contains("gl_VertexID") && !source.contains("gl_BaseVertex");
//$$                 }
//$$                 else if (kind == GL20.GL_FRAGMENT_SHADER)
//$$                     fragment = NativeShaderSideEffects.hasOnlyRasterOutputs(GL20.glGetShaderSource(shader));
//$$                 else return unknown;
//$$             }
//$$             return new Proof(program, vertex && fragment, vertex && fragment && baseVertexSafe);
//$$         }
//$$     }
//$$ }
//#else
public final class MaskBoundsTracker { private MaskBoundsTracker() {} }
//#endif
