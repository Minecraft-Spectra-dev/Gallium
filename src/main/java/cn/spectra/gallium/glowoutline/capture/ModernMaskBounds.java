package cn.spectra.gallium.glowoutline.capture;

//#if MC>=1_21_05 && MC<1_26_02
import cn.spectra.gallium.glowoutline.IrisCompat;
import cn.spectra.gallium.glowoutline.shader.GlowResources;
import cn.spectra.gallium.glowoutline.shader.VertexPositionProof;
import cn.spectra.gallium.glowoutline.shader.NativeShaderSideEffects;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.util.HashMap;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

/** Observes a native RenderType draw after layering, uniform upload and program binding. */
public final class ModernMaskBounds implements AutoCloseable {
    private static final HashMap<Integer, Boolean> programs = new HashMap<>();
    private static ModernMaskBounds current;
    private static final ModernMaskBounds inactive = new ModernMaskBounds(), worker = new ModernMaskBounds();
    private final Matrix4f modelView = new Matrix4f(), projection = new Matrix4f();
    private ProjectedMaskBounds bounds;
    private GlowCaptureState state;
    private TextureTarget target;
    private OpenGlMaskOrdering.Stamp ordering;
    private MeshData pending;
    private boolean nativeDraw;
    static { GlowResources.register(() -> { programs.clear(); current = null; }); }
    private ModernMaskBounds() {}

    public static ModernMaskBounds begin(GlowCaptureState state, TextureTarget mask, boolean ordinary) {
        state.maskBounds.begin(mask.width, mask.height);
        if (current != null) { current.bounds.invalidate(); state.maskBounds.invalidate(); return inactive; }
        if (!ordinary || state.guiEntity != null || IrisCompat.isActiveSrRuntime()
                //#if MC==1_21_10 || MC==1_21_11 || MC==1_26_01
                || (IrisCompat.isShaderActive() && !cn.spectra.gallium.glowoutline.shader.OriginalGlowParameters
                        .supportsDeferredSceneOcclusion(state.config))
                //#elseif MC>=1_21_06 && MC!=1_21_08
                //$$ || IrisCompat.isShaderActive()
                //#endif
        ) {
            state.maskBounds.invalidate(); return inactive;
        }
        var ordering = OpenGlMaskOrdering.observe();
        if (ordering == null) { state.maskBounds.invalidate(); return inactive; }
        var scope = worker;
        scope.bounds = state.maskBounds; scope.state = state; scope.target = mask; scope.ordering = ordering;
        current = scope;
        return scope;
    }

    /** Called with the actual matrix passed to native DynamicUniforms.writeTransform. */
    public static void transformed(MeshData mesh, Matrix4fc matrix) {
        //#if MC==1_21_11
        //$$ cn.spectra.gallium.glowoutline.shader.OutlineSrCapture.transformed(mesh, matrix);
        //#endif
        var scope = current;
        if (scope == null || !scope.bounds.accepting()) return;
        if (scope.pending != null) { scope.bounds.invalidate(); return; }
        scope.pending = mesh;
        scope.modelView.set(matrix);
    }

    public static boolean observing(MeshData mesh) {
        return current != null && current.bounds.accepting() && current.pending == mesh;
    }

    //#if MC==1_21_08 || MC==1_21_11 || MC==1_26_01
    public static GlowCaptureState currentState() {
        var scope = current;
        return scope != null && scope.bounds.accepting() && scope.ordering.current()
                && RenderSystem.outputColorTextureOverride == scope.target.getColorTextureView()
                && RenderSystem.outputDepthTextureOverride == scope.target.getDepthTextureView() ? scope.state : null;
    }

    //#endif

    /** Every native backend draw must have exactly one observed mesh and transform. */
    public static void nativeDraw() {
        var scope = current;
        if (scope == null || !scope.bounds.accepting()) return;
        if (scope.pending == null || scope.nativeDraw) scope.bounds.invalidate();
        else scope.nativeDraw = true;
    }

    /** The native draw has returned; its program and projection are still bound. */
    public static void drawn(MeshData mesh, VertexFormat pipelineFormat
            //#if MC<1_21_06
            //$$ , TextureTarget output
            //#endif
    ) {
        //#if MC==1_21_11
        //$$ cn.spectra.gallium.glowoutline.shader.OutlineSrCapture.drawn(mesh, pipelineFormat);
        //#endif
        var scope = current;
        if (scope == null || !scope.bounds.accepting()) return;
        if (scope.pending != mesh || !scope.nativeDraw) { scope.bounds.invalidate(); return; }
        scope.pending = null;
        scope.nativeDraw = false;
        try {
            var draw = mesh.drawState();
            var format = draw.format();
            if (!scope.ordering.current() || format != pipelineFormat
                    //#if MC>=1_21_06
                    || RenderSystem.outputColorTextureOverride != scope.target.getColorTextureView()
                    || RenderSystem.outputDepthTextureOverride != scope.target.getDepthTextureView()
                    || ProjectionMatrixTracker.lookupInto(RenderSystem.getProjectionMatrixBuffer(), scope.projection) == null
                    //#else
                    //$$ || output != scope.target
                    //#endif
                    || !format.contains(VertexFormatElement.POSITION)
                    || (draw.mode() != VertexFormat.Mode.QUADS && draw.mode() != VertexFormat.Mode.TRIANGLES
                        && draw.mode() != VertexFormat.Mode.TRIANGLE_STRIP && draw.mode() != VertexFormat.Mode.TRIANGLE_FAN)) {
                scope.bounds.invalidate(); return;
            }
            //#if MC<1_21_06
            //$$ scope.projection.set(RenderSystem.getProjectionMatrix());
            //#endif
            int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            if (programs.size() >= 128 && !programs.containsKey(program)) programs.clear();
            if (!programs.computeIfAbsent(program, ModernMaskBounds::inspect)) {
                scope.bounds.invalidate(); return;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var viewport = stack.mallocInt(4); GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
                if (viewport.get(0) != 0 || viewport.get(1) != 0
                        || viewport.get(2) != scope.target.width || viewport.get(3) != scope.target.height) {
                    scope.bounds.invalidate(); return;
                }
            }
            scope.bounds.include(mesh.vertexBuffer(), format.getVertexSize(), draw.vertexCount(),
                    format.getOffset(VertexFormatElement.POSITION), scope.modelView, scope.projection);
            //#if MC==1_21_08
            //$$ cn.spectra.gallium.glowoutline.shader.OutlineTemporalStabilizer.mesh(scope.state, mesh, scope.modelView, scope.projection, program);
            //#endif
        } catch (RuntimeException | LinkageError unavailable) {
            scope.bounds.invalidate();
        }
    }

    private static boolean inspect(int program) {
        if (program <= 0 || GL20.glGetProgrami(program, GL20.GL_ATTACHED_SHADERS) != 2) return false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var count = stack.mallocInt(1); var shaders = stack.mallocInt(2);
            GL20.glGetAttachedShaders(program, count, shaders);
            if (count.get(0) != 2) return false;
            boolean vertex = false, fragment = false;
            for (int i = 0; i < 2; i++) {
                int shader = shaders.get(i), kind = GL20.glGetShaderi(shader, GL20.GL_SHADER_TYPE);
                if (kind == GL20.GL_VERTEX_SHADER) {
                    //#if MC>=1_21_06
                    vertex = VertexPositionProof.matchesUniformBlocks(GL20.glGetShaderSource(shader));
                    //#else
                    //$$ vertex = VertexPositionProof.matches(GL20.glGetShaderSource(shader));
                    //#endif
                }
                else if (kind == GL20.GL_FRAGMENT_SHADER)
                    fragment = NativeShaderSideEffects.hasOnlyRasterOutputs(GL20.glGetShaderSource(shader));
                else return false;
            }
            return vertex && fragment;
        }
    }

    @Override public void close() {
        if (this == inactive) return;
        if (pending != null || nativeDraw) bounds.invalidate();
        pending = null;
        nativeDraw = false;
        if (current == this) current = null;
        bounds = null; state = null; target = null; ordering = null;
    }
}
//#else
//$$ public final class ModernMaskBounds { private ModernMaskBounds() {} }
//#endif
