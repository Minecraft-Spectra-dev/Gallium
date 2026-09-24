package cn.spectra.gallium.glowoutline.shader;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UniformReadExtentTest {
    @Test void scalarAndVectorTailDoNotRequireUnreadBlockPadding() {
        assertEquals(40, UniformReadExtent.end(36, 4, 1, 1, 1, 0, 0, false));
        assertEquals(28, UniformReadExtent.end(16, 4, 1, 3, 1, 0, 0, false));
    }
    @Test void nonSquareMatricesRespectReflectedMajorOrder() {
        assertEquals(28, UniformReadExtent.end(0, 4, 2, 3, 1, 0, 16, false));
        assertEquals(40, UniformReadExtent.end(0, 4, 2, 3, 1, 0, 16, true));
        assertEquals(40, UniformReadExtent.end(0, 4, 3, 2, 1, 0, 16, false));
        assertEquals(28, UniformReadExtent.end(0, 4, 3, 2, 1, 0, 16, true));
    }
    @Test void arraysIncludeInteriorPaddingAndOnlyTheLastElementPayload() {
        assertEquals(44, UniformReadExtent.end(0, 4, 1, 3, 3, 16, 0, false));
        assertEquals(108, UniformReadExtent.end(16, 4, 3, 3, 2, 48, 16, false));
        assertEquals(48, UniformReadExtent.end(0, 8, 1, 2, 3, 16, 0, false));
    }
    @Test void invalidOrOverflowingReflectionCannotAuthorizeAnUndersizedBuffer() {
        assertEquals(-1, UniformReadExtent.end(-1, 4, 1, 1, 1, 0, 0, false));
        assertEquals(-1, UniformReadExtent.end(0, 4, 1, 1, 0, 0, 0, false));
        assertEquals(-1, UniformReadExtent.end(0, 4, 1, 4, 2, 12, 0, false));
        assertEquals(-1, UniformReadExtent.end(0, 4, 2, 3, 1, 0, 8, false));
        assertEquals(-1, UniformReadExtent.end(Long.MAX_VALUE, 4, 1, 1, 1, 0, 0, false));
        assertEquals(-1, UniformReadExtent.end(0, 4, 1, 1, Long.MAX_VALUE, 16, 0, false));
        assertEquals(-1, UniformReadExtent.end(0, 3, 1, 1, 1, 0, 0, false));
    }
}
