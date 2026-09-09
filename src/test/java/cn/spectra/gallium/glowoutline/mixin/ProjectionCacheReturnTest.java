package cn.spectra.gallium.glowoutline.mixin;

//#if MC==1_21_11 || MC==1_26_01
import cn.spectra.gallium.glowoutline.capture.ProjectionMatrixTracker;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
//#if MC==1_21_11
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//#endif
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real mixin callback bodies with identity-only slices; no GPU objects or GL. */
class ProjectionCacheReturnTest {
    private Field renderThread;
    private Object previousRenderThread;

    @BeforeEach
    void bindTestThread() throws Exception {
        renderThread = RenderSystem.class.getDeclaredField("renderThread");
        renderThread.setAccessible(true);
        previousRenderThread = renderThread.get(null);
        renderThread.set(null, Thread.currentThread());
        ProjectionMatrixTracker.clear();
    }

    @AfterEach
    void restoreTestThread() throws Exception {
        try { ProjectionMatrixTracker.clear(); }
        finally { renderThread.set(null, previousRenderThread); }
    }

    private static Object bufferCallbacks() {
        //#if MC==1_26_01
        return new ProjectionMatrixBufferMixin();
        //#else
        //$$ return new CachedPerspectiveProjectionMatrixBufferMixin();
        //#endif
    }

    private static void invoke(Object callbacks, String name, Object... arguments) throws Exception {
        for (Method method : callbacks.getClass().getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                method.setAccessible(true);
                method.invoke(callbacks, arguments);
                return;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static CallbackInfoReturnable<GpuBufferSlice> returned(GpuBufferSlice slice) {
        return new CallbackInfoReturnable<>("getBuffer", false, slice);
    }

    private static void createdWithoutSuccessfulReturn(Object callbacks, Matrix4f matrix) throws Exception {
        //#if MC==1_26_01
        invoke(callbacks, "galliumBeginUpload", returned(null));
        //#else
        //$$ invoke(callbacks, "galliumBeginCachedProjection", returned(null));
        //$$ invoke(callbacks, "galliumCaptureMatrix", null, 854, 480, 70.0f,
        //$$         (Operation<Matrix4f>) arguments -> matrix);
        //#endif
    }

    private static void uploaded(Object callbacks, Matrix4f matrix, GpuBufferSlice slice) throws Exception {
        //#if MC==1_26_01
        createdWithoutSuccessfulReturn(callbacks, matrix);
        invoke(callbacks, "galliumRememberMatrix", matrix, returned(slice));
        //#else
        //$$ createdWithoutSuccessfulReturn(callbacks, matrix);
        //$$ invoke(callbacks, "galliumAssociateSlice", 854, 480, 70.0f, returned(slice));
        //#endif
    }

    private static void cacheHit(Object callbacks, GpuBufferSlice slice) throws Exception {
        //#if MC==1_26_01
        invoke(callbacks, "galliumRestoreCachedMatrix", returned(slice));
        //#else
        //$$ invoke(callbacks, "galliumBeginCachedProjection", returned(null));
        //$$ invoke(callbacks, "galliumAssociateSlice", 854, 480, 70.0f, returned(slice));
        //#endif
    }

    @Test
    void actualUploadThenTrackerReloadThenCacheHitRestoresCapturedMatrix() throws Exception {
        Object callbacks = bufferCallbacks();
        var slice = new GpuBufferSlice(null, 0L, 64L);
        var matrix = new Matrix4f().perspective(1.1f, 1.8f, 0.05f, 100.0f);
        var expected = new Matrix4f(matrix);
        uploaded(callbacks, matrix, slice);
        assertEquals(expected, ProjectionMatrixTracker.lookup(slice));
        matrix.identity(); // The vanilla caller may reuse its mutable matrix after upload.
        ProjectionMatrixTracker.clear();
        assertNull(ProjectionMatrixTracker.lookup(slice));
        cacheHit(callbacks, slice);
        assertEquals(expected, ProjectionMatrixTracker.lookup(slice));
    }

    @Test
    void twoBufferInstancesRestoreOnlyTheirOwnLastSuccessfulUpload() throws Exception {
        Object world = bufferCallbacks(), hand = bufferCallbacks();
        var worldSlice = new GpuBufferSlice(null, 0L, 64L);
        var handSlice = new GpuBufferSlice(null, 0L, 64L);
        var worldMatrix = new Matrix4f().perspective(1.0f, 1.8f, 0.05f, 1000.0f);
        var handMatrix = new Matrix4f().perspective(1.3f, 1.8f, 0.05f, 100.0f);
        uploaded(world, worldMatrix, worldSlice);
        uploaded(hand, handMatrix, handSlice);
        ProjectionMatrixTracker.clear();
        cacheHit(hand, handSlice);
        assertNull(ProjectionMatrixTracker.lookup(worldSlice));
        cacheHit(world, worldSlice);
        assertEquals(worldMatrix, ProjectionMatrixTracker.lookup(worldSlice));
        assertEquals(handMatrix, ProjectionMatrixTracker.lookup(handSlice));
        var replacement = new Matrix4f().perspective(1.4f, 1.8f, 0.05f, 500.0f);
        uploaded(world, replacement, worldSlice);
        ProjectionMatrixTracker.clear();
        cacheHit(world, worldSlice);
        cacheHit(hand, handSlice);
        assertEquals(replacement, ProjectionMatrixTracker.lookup(worldSlice));
        assertEquals(handMatrix, ProjectionMatrixTracker.lookup(handSlice));
    }

    @Test
    void cacheHitWithoutObservedUploadDoesNotInventAProjection() throws Exception {
        var slice = new GpuBufferSlice(null, 0L, 64L);
        cacheHit(bufferCallbacks(), slice);
        assertNull(ProjectionMatrixTracker.lookup(slice));
    }

    @Test
    void failedUploadRevokesProofInsteadOfRestoringTheOldMatrix() throws Exception {
        Object callbacks = bufferCallbacks();
        var slice = new GpuBufferSlice(null, 0L, 64L);
        var successful = new Matrix4f().perspective(1.0f, 1.8f, 0.05f, 100.0f);
        uploaded(callbacks, successful, slice);
        createdWithoutSuccessfulReturn(callbacks, new Matrix4f().zero());
        assertNull(ProjectionMatrixTracker.lookup(slice), "native contents may already have changed");
        ProjectionMatrixTracker.clear();
        cacheHit(callbacks, slice);
        assertNull(ProjectionMatrixTracker.lookup(slice));
        var recovered = new Matrix4f().perspective(1.2f, 1.8f, 0.05f, 100.0f);
        uploaded(callbacks, recovered, slice);
        ProjectionMatrixTracker.clear();
        cacheHit(callbacks, slice);
        assertEquals(recovered, ProjectionMatrixTracker.lookup(slice));
    }

    @Test
    void failedUploadRevokesOnlyThatBufferInstance() throws Exception {
        Object first = bufferCallbacks(), second = bufferCallbacks();
        var firstSlice = new GpuBufferSlice(null, 0L, 64L);
        var secondSlice = new GpuBufferSlice(null, 0L, 64L);
        var firstMatrix = new Matrix4f().perspective(1.0f, 1.8f, 0.05f, 100.0f);
        var secondMatrix = new Matrix4f().perspective(1.3f, 1.8f, 0.05f, 1000.0f);
        uploaded(first, firstMatrix, firstSlice);
        uploaded(second, secondMatrix, secondSlice);
        createdWithoutSuccessfulReturn(first, new Matrix4f().zero());
        assertNull(ProjectionMatrixTracker.lookup(firstSlice));
        assertEquals(secondMatrix, ProjectionMatrixTracker.lookup(secondSlice));
        ProjectionMatrixTracker.clear();
        cacheHit(first, firstSlice);
        cacheHit(second, secondSlice);
        assertNull(ProjectionMatrixTracker.lookup(firstSlice));
        assertEquals(secondMatrix, ProjectionMatrixTracker.lookup(secondSlice));
    }

    @Test
    void differentCachedSliceCannotBorrowAnOldBuffersMatrix() throws Exception {
        Object callbacks = bufferCallbacks();
        var uploadedSlice = new GpuBufferSlice(null, 0L, 64L);
        var unobservedSlice = new GpuBufferSlice(null, 0L, 64L);
        uploaded(callbacks, new Matrix4f(), uploadedSlice);
        cacheHit(callbacks, unobservedSlice);
        assertNull(ProjectionMatrixTracker.lookup(unobservedSlice));
    }

    @Test
    void revokingAndReestablishingProofReusesTheAssociationAndMatrixStorage() throws Exception {
        var slice = new GpuBufferSlice(null, 0L, 64L);
        var initial = new Matrix4f().perspective(1.0f, 1.8f, 0.05f, 100.0f);
        ProjectionMatrixTracker.remember(slice, initial);
        Field associations = ProjectionMatrixTracker.class.getDeclaredField("ASSOCIATIONS");
        associations.setAccessible(true);
        var entries = (java.util.Map<?, ?>) associations.get(null);
        Object entry = entries.get(slice);
        Field matrixStorage = entry.getClass().getDeclaredField("matrix");
        matrixStorage.setAccessible(true);
        Object storedMatrix = matrixStorage.get(entry);
        var destination = new Matrix4f().translation(2.0f, 3.0f, 4.0f);
        var untouched = new Matrix4f(destination);

        ProjectionMatrixTracker.forget(slice);
        assertSame(entry, entries.get(slice));
        assertSame(storedMatrix, matrixStorage.get(entry));
        assertNull(ProjectionMatrixTracker.lookup(slice));
        assertNull(ProjectionMatrixTracker.lookupInto(slice, destination));
        assertEquals(untouched, destination);

        var replacement = new Matrix4f().perspective(1.3f, 1.8f, 0.05f, 200.0f);
        ProjectionMatrixTracker.remember(slice, replacement);
        assertSame(entry, entries.get(slice));
        assertSame(storedMatrix, matrixStorage.get(entry));
        assertEquals(replacement, ProjectionMatrixTracker.lookup(slice));
        ProjectionMatrixTracker.clear();
        assertTrue(entries.isEmpty(), "reload still drops all slice and matrix references");
    }
}
//#endif
