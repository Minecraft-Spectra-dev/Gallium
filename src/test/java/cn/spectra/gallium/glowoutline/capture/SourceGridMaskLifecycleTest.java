package cn.spectra.gallium.glowoutline.capture;

//#if MC==1_21_11 || MC==1_26_01
import com.mojang.blaze3d.pipeline.TextureTarget;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceGridMaskLifecycleTest {
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    static class RetiredSource extends TextureTarget {
        int closes;
        Runnable beforeClose;
        RuntimeException failure;
        RetiredSource() { super("unused-test-constructor", 1, 1, true); }
        @Override public void destroyBuffers() {
            closes++;
            if (beforeClose != null) beforeClose.run();
            if (failure != null) throw failure;
        }
    }

    @Test
    void sourceRetirementIsIdempotentEvenWhenNativeDisposalThrows() throws Exception {
        var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        var target = (RetiredSource) unsafe.allocateInstance(RetiredSource.class);
        var nativeTarget = (RetiredSource) unsafe.allocateInstance(RetiredSource.class);
        var owner = field(GlowCaptureManager.class, "sourceGridMaskTarget");
        var nativeOwner = field(GlowCaptureManager.class, "nativeCoverageMaskTarget");
        var reservation = field(GlowCaptureManager.class, "sourceGridMaskBytesReserved");
        var release = GlowCaptureManager.class.getDeclaredMethod("releaseSourceGridMask");
        release.setAccessible(true);
        Object savedOwner = owner.get(null);
        Object savedNativeOwner = nativeOwner.get(null);
        long savedReservation = reservation.getLong(null);
        try {
            owner.set(null, target);
            nativeOwner.set(null, nativeTarget);
            reservation.setLong(null, 8192);
            target.beforeClose = () -> {
                try {
                    assertNull(owner.get(null));
                    assertNull(nativeOwner.get(null));
                    assertEquals(0, reservation.getLong(null));
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            };
            target.failure = new IllegalStateException("simulated native close failure");
            assertSame(target.failure, assertThrows(InvocationTargetException.class,
                    () -> release.invoke(null)).getCause());
            release.invoke(null);
            assertEquals(1, target.closes);
            assertEquals(1, nativeTarget.closes);
            assertNull(owner.get(null));
            assertNull(nativeOwner.get(null));
            assertEquals(0, reservation.getLong(null));
        } finally {
            owner.set(null, savedOwner);
            nativeOwner.set(null, savedNativeOwner);
            reservation.setLong(null, savedReservation);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void independentFallbackReservationsCountBothScratchMasksWithoutRaisingTheCap() throws Exception {
        var poolField = field(GlowCaptureManager.class, "pool");
        var pool = (List<GlowCaptureState>) poolField.get(null);
        var savedPool = new ArrayList<>(pool);
        var total = field(GlowCaptureManager.class, "captureTargetBytesReserved");
        var source = field(GlowCaptureManager.class, "sourceGridMaskBytesReserved");
        long savedTotal = total.getLong(null), savedSource = source.getLong(null);
        var reserve = GlowCaptureManager.class.getDeclaredMethod(
                "reserveCaptureTarget", GlowCaptureState.class, int.class, int.class);
        reserve.setAccessible(true);
        long cap = GlowCaptureManager.CAPTURE_TARGET_BUDGET_BYTES;
        long sourceBytes = GlowCaptureManager.estimatedCaptureTargetBytes(427, 240, 8)
                + GlowCaptureManager.estimatedCaptureTargetBytes(854, 480, 8);
        long requested = GlowCaptureManager.estimatedCaptureTargetBytes(854, 480, 8);
        var state = new GlowCaptureState();
        try {
            pool.clear();
            source.setLong(null, sourceBytes);
            total.setLong(null, cap - sourceBytes - requested + 1);
            assertEquals(false, reserve.invoke(null, state, 854, 480));
            assertEquals(0, state.captureTargetBytesReserved);
            assertEquals(cap - sourceBytes - requested + 1, total.getLong(null));
            total.setLong(null, cap - sourceBytes - requested);
            assertEquals(true, reserve.invoke(null, state, 854, 480));
            assertEquals(requested, state.captureTargetBytesReserved);
            assertEquals(cap, total.getLong(null) + source.getLong(null));
            assertEquals(sourceBytes, source.getLong(null));
        } finally {
            pool.clear(); pool.addAll(savedPool);
            total.setLong(null, savedTotal); source.setLong(null, savedSource);
        }
    }
}
//#endif
