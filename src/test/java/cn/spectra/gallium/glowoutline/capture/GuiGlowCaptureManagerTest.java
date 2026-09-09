package cn.spectra.gallium.glowoutline.capture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GuiGlowCaptureManagerTest {

    @AfterEach
    void resetManager() {
        GuiGlowCaptureManager.clear();
    }

    @Test
    void indexedAccessTracksOnlyTheActivePrefix() {
        assertEquals(0, GuiGlowCaptureManager.activeCount());

        GuiGlowCapture first = GuiGlowCaptureManager.acquire();
        GuiGlowCapture second = GuiGlowCaptureManager.acquire();

        assertEquals(2, GuiGlowCaptureManager.activeCount());
        assertSame(first, GuiGlowCaptureManager.activeAt(0));
        assertSame(second, GuiGlowCaptureManager.activeAt(1));
        assertThrows(IndexOutOfBoundsException.class,
                () -> GuiGlowCaptureManager.activeAt(-1));
        assertThrows(IndexOutOfBoundsException.class,
                () -> GuiGlowCaptureManager.activeAt(2));
    }

    @Test
    void savedLimitPreservesSnapshotIterationSemantics() {
        GuiGlowCapture first = GuiGlowCaptureManager.acquire();
        int limit = GuiGlowCaptureManager.activeCount();

        GuiGlowCaptureManager.acquire();

        assertEquals(1, limit);
        assertEquals(2, GuiGlowCaptureManager.activeCount());
        assertSame(first, GuiGlowCaptureManager.activeAt(0));
    }

    @Test
    void clearResetsCountAndReusesThePoolPrefix() {
        GuiGlowCapture first = GuiGlowCaptureManager.acquire();

        GuiGlowCaptureManager.clear();

        assertEquals(0, GuiGlowCaptureManager.activeCount());
        assertSame(first, GuiGlowCaptureManager.acquire());
    }
}
