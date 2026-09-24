package cn.spectra.gallium.glowoutline.shader;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UniformUploadCacheTest {
    @Test void firstValueMustBeUploadedEvenWhenItIsZero() {
        var history = new UniformUploadCache(4);
        var values = FloatBuffer.allocate(4);
        assertFalse(history.matches(7, values));
        history.uploaded(7, values);
        assertTrue(history.matches(7, values));
    }

    @Test void changingOneMatrixComponentRequiresAnotherUpload() {
        var history = new UniformUploadCache(16);
        var matrix = FloatBuffer.allocate(16);
        matrix.put(0, 1).put(5, 1).put(10, 1).put(15, 1);
        history.uploaded(2, matrix);
        matrix.position(12); // Native upload rewinds/clears its view.
        assertTrue(history.matches(2, matrix));
        matrix.put(12, .125f); // Also catches direct modifications through getFloatBuffer().
        assertFalse(history.matches(2, matrix));
        history.uploaded(2, matrix);
        assertTrue(history.matches(2, matrix));
    }

    @Test void signedZeroAndNanPayloadsAreNotCollapsed() {
        var history = new UniformUploadCache(1);
        var value = FloatBuffer.allocate(1);
        history.uploaded(3, value);
        value.put(0, -0.0f);
        assertFalse(history.matches(3, value));
        value.put(0, Float.intBitsToFloat(0x7fc00001));
        history.uploaded(3, value);
        assertTrue(history.matches(3, value));
        value.put(0, Float.intBitsToFloat(0x7fc00002));
        assertFalse(history.matches(3, value));
    }

    @Test void relinkingOrChangingTheUploadLengthInvalidatesTheHistory() {
        var history = new UniformUploadCache(4);
        var values = IntBuffer.wrap(new int[] {1, 2, 3, 4});
        history.uploaded(5, values);
        assertFalse(history.matches(6, values));
        values.limit(2);
        assertFalse(history.matches(5, values));
        history.uploaded(5, values);
        assertTrue(history.matches(5, values));
        values.limit(4);
        assertFalse(history.matches(5, values));
        history.invalidate();
        assertFalse(history.matches(5, values));
    }

    @Test void integerParametersAndOverflowRemainConservative() {
        var history = new UniformUploadCache(2);
        var values = IntBuffer.wrap(new int[] {2, 5});
        history.uploaded(1, values);
        values.put(1, 6);
        assertFalse(history.matches(1, values));
        history.uploaded(1, IntBuffer.allocate(4));
        assertFalse(history.matches(1, values));
    }
}
