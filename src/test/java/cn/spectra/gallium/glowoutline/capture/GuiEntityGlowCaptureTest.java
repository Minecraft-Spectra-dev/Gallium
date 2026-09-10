package cn.spectra.gallium.glowoutline.capture;

import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GuiEntityGlowCaptureTest {
    private static final ItemEffectConfig EFFECT = new ItemEffectConfig("test", List.of());

    @Test void previewGeometryDoesNotJoinTheWorldQueueOrRequestWorldDepth() {
        int worldCount = GlowCaptureManager.getActiveStates().size();
        boolean worldDepthNeeded = GlowCaptureManager.needsSceneDepthCapture();
        try (var preview = GuiEntityGlowCapture.begin(100, 200, 50)) {
            var state = preview.acquire(EFFECT);
            assertNotNull(state);
            GlowCaptureManager.markCaptured(state);
            assertTrue(state.capturedThisFrame);
            assertEquals(worldCount, GlowCaptureManager.getActiveStates().size());
            assertEquals(worldDepthNeeded, GlowCaptureManager.needsSceneDepthCapture());
        }
    }

    @Test void nestedPreviewFailureRestoresItsParentAndDiscardsOnlyItsOwnCaptures() {
        try (var parent = GuiEntityGlowCapture.begin(100, 200, 50)) {
            var first = parent.acquire(EFFECT);
            assertNotNull(first);
            assertEquals(0.5f, first.itemWorldToUv.x);
            assertEquals(0.25f, first.itemWorldToUv.y);
            assertThrows(IllegalArgumentException.class, () -> {
                try (var child = GuiEntityGlowCapture.begin(50, 50, 25)) {
                    var second = child.acquire(EFFECT);
                    assertNotSame(first, second);
                    assertSame(child, second.guiEntity);
                    throw new IllegalArgumentException("render failure");
                }
            });
            assertSame(parent, GuiEntityGlowCapture.current());
            assertSame(parent, first.guiEntity);
            assertTrue(first.hasOpenCaptureScope());
        }
        assertNull(GuiEntityGlowCapture.current());
    }

    @Test void disabledOrEmptyPreviewsCannotAllocateAnItemCapture() {
        try (var preview = GuiEntityGlowCapture.begin(0, 0, 30)) {
            assertNull(preview.acquire(EFFECT));
        }
    }

    @Test void closingAnOuterScopeTooEarlyDoesNotLoseEitherOwner() {
        var outer = GuiEntityGlowCapture.begin(10, 10, 10);
        var inner = GuiEntityGlowCapture.begin(10, 10, 10);
        assertThrows(IllegalStateException.class, outer::close);
        assertSame(inner, GuiEntityGlowCapture.current());
        inner.close();
        assertSame(outer, GuiEntityGlowCapture.current());
        outer.close();
        outer.close();
        assertNull(GuiEntityGlowCapture.current());
    }
}
