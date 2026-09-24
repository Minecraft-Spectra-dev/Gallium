package cn.spectra.gallium.glowoutline.capture;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaskAtlasLayoutTest {
    @Test void failedPlacementsDoNotConsumeStorageAndResetReclaimsThePage() {
        var layout = new MaskAtlasLayout(8, 8);
        assertEquals(new MaskAtlasLayout.Tile(0, 0, 6, 3), layout.place(6, 3));
        assertNull(layout.place(9, 1));
        assertNull(layout.place(3, 6));
        assertEquals(new MaskAtlasLayout.Tile(6, 0, 2, 4), layout.place(2, 4));
        assertEquals(new MaskAtlasLayout.Tile(0, 4, 8, 4), layout.place(8, 4));
        assertNull(layout.place(1, 1));
        assertEquals(new MaskAtlasLayout.Tile(0, 0, 0, 0), layout.place(0, 8));
        layout.reset();
        assertEquals(new MaskAtlasLayout.Tile(0, 0, 8, 8), layout.place(8, 8));
    }
}
