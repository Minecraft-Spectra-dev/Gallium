package cn.spectra.gallium.glowoutline.capture;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VertexUploadBudgetTest {
    @Test void exactCapacityIncludesAlignmentBetweenDifferentVertexFormats() {
        var budget = new VertexUploadBudget(28, 144);
        assertTrue(budget.append(24, 4)); // Starts at48, consumes96 bytes.
        assertFalse(budget.append(4, 1));
    }
    @Test void partialPlanFailureCannotBeReusedAsACompletePlan() {
        var budget = new VertexUploadBudget(0, 144);
        assertTrue(budget.append(36, 4));
        assertFalse(budget.append(36, 4));
        assertFalse(budget.valid());
        assertFalse(budget.append(1, 1));
    }
    @Test void oversizedProductCannotWrapIntoAvailableCapacity() {
        assertFalse(new VertexUploadBudget(0, 4 * 1024 * 1024).append(Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertFalse(new VertexUploadBudget(Integer.MAX_VALUE - 1, Integer.MAX_VALUE).append(36, 4));
    }
    @Test void invalidDimensionsAndInitialRangeFailClosed() {
        assertFalse(new VertexUploadBudget(-1, 4096).valid());
        assertFalse(new VertexUploadBudget(4097, 4096).valid());
        assertFalse(new VertexUploadBudget(0, 4096).append(0, 4));
        assertFalse(new VertexUploadBudget(0, 4096).append(36, 0));
        assertFalse(new VertexUploadBudget(0, 4096).append(36, -1));
    }
}
