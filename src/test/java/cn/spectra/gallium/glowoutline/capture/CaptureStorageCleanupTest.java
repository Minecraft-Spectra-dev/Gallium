package cn.spectra.gallium.glowoutline.capture;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaptureStorageCleanupTest {
    @Test void skipsOnlyTheExactStorageAlreadyCleared() {
        var cleanup=new CaptureStorageCleanup();var first=new Object();var second=new Object();var calls=new AtomicInteger();
        cleanup.clear(first,calls::incrementAndGet);cleanup.clear(first,calls::incrementAndGet);
        assertEquals(1,calls.get());assertTrue(cleanup.isClear(first));assertFalse(cleanup.isClear(second));
        cleanup.clear(second,calls::incrementAndGet);assertEquals(2,calls.get());
        cleanup.dirty();cleanup.clear(second,calls::incrementAndGet);assertEquals(3,calls.get());
    }

    @Test void aNativeDrainProofIsInvalidatedBeforeTheNextWrite() {
        var cleanup=new CaptureStorageCleanup();var nodes=new ArrayList<Integer>();
        cleanup.cleared(nodes);assertTrue(cleanup.isClear(nodes));
        cleanup.dirty();nodes.add(42);cleanup.clear(nodes,nodes::clear);
        assertTrue(nodes.isEmpty());assertTrue(cleanup.isClear(nodes));
        cleanup.forget();assertFalse(cleanup.isClear(nodes));
    }

    @Test void partialFailureCanBeRetriedWithoutLosingRemainingNodes() {
        var cleanup=new CaptureStorageCleanup();var nodes=new ArrayList<>(java.util.List.of(1,2));
        assertThrows(IllegalStateException.class,()->cleanup.clear(nodes,()->{nodes.remove(0);throw new IllegalStateException();}));
        assertFalse(cleanup.isClear(nodes));assertEquals(java.util.List.of(2),nodes);
        cleanup.clear(nodes,nodes::clear);assertTrue(nodes.isEmpty());assertTrue(cleanup.isClear(nodes));
    }

    @Test void writesDuringCleanupRemainDirtyForTheNextCleanup() {
        var cleanup=new CaptureStorageCleanup();var nodes=new ArrayList<>(java.util.List.of(1));
        cleanup.clear(nodes,()->{nodes.clear();cleanup.dirty();nodes.add(2);});
        assertFalse(cleanup.isClear(nodes));assertEquals(java.util.List.of(2),nodes);
        cleanup.clear(nodes,nodes::clear);assertTrue(nodes.isEmpty());assertTrue(cleanup.isClear(nodes));
    }

    @Test void nullStorageIsNeverProofOfClearedWork() {
        var cleanup=new CaptureStorageCleanup();cleanup.cleared(null);
        assertFalse(cleanup.isClear(null));cleanup.clear(null,()->fail("No storage to clear"));
    }
}
