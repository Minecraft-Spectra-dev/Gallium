package cn.spectra.gallium.glowoutline.sr;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SuperResolutionTextureFilterTest {
    @Test
    void mipmappedMagnificationKeepsItsWithinLevelFilter() {
        assertEquals(0x2600, SuperResolutionTextureFilter.legalParameter(0x2800, 0x2700));
        assertEquals(0x2601, SuperResolutionTextureFilter.legalParameter(0x2800, 0x2701));
        assertEquals(0x2600, SuperResolutionTextureFilter.legalParameter(0x2800, 0x2702));
        assertEquals(0x2601, SuperResolutionTextureFilter.legalParameter(0x2800, 0x2703));
    }

    @Test
    void minificationRetainsEveryMipmapFilter() {
        for (int value = 0x2700; value <= 0x2703; value++) {
            assertEquals(value, SuperResolutionTextureFilter.legalParameter(0x2801, value));
        }
    }

    @Test
    void legalMagnificationAndUnknownValuesAreUnchanged() {
        for (int value : new int[] {0x2600, 0x2601, -1, 0}) {
            assertEquals(value, SuperResolutionTextureFilter.legalParameter(0x2800, value));
        }
    }

    @Test
    void otherTextureParametersAreUntouched() {
        assertEquals(0x2701, SuperResolutionTextureFilter.legalParameter(0x813D, 0x2701));
    }
}
