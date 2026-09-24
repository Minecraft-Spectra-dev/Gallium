package cn.spectra.gallium.glowoutline;

import org.junit.jupiter.api.Test;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class IrisLegacyBypassTest {
    @Test void fallbackIsNestedThreadLocalAndRestoredOnUnwind() throws Exception {
        var observed = IrisCompat.class.getDeclaredField("legacyBypassHookObserved");
        observed.setAccessible(true);
        boolean previous = observed.getBoolean(null);
        IrisCompat.BypassSnapshot outer = null;
        try {
            assertFalse(IrisCompat.legacyShaderBypassForCall());
            outer = IrisCompat.setBypass(true);
            assertTrue(outer.shaderBypassEnabled());
            assertTrue(IrisCompat.legacyShaderBypassForCall());
            var executor = Executors.newSingleThreadExecutor();
            try {
                assertFalse(executor.submit(IrisCompat::legacyShaderBypassForCall).get());
            } finally { executor.shutdownNow(); }
            var inner = IrisCompat.setBypass(false);
            try { assertFalse(IrisCompat.legacyShaderBypassForCall()); }
            finally { IrisCompat.restoreBypass(inner); }
            assertTrue(IrisCompat.legacyShaderBypassForCall());
        } finally {
            IrisCompat.restoreBypass(outer);
            assertFalse(IrisCompat.legacyShaderBypassForCall());
            observed.setBoolean(null, previous);
        }
    }

    @Test void existingSnapshotConstructorDoesNotClaimLegacyState() {
        var snapshot = new IrisCompat.BypassSnapshot(false, false, false, false, false);
        assertFalse(snapshot.valid());
        assertFalse(snapshot.legacyValid());
        assertFalse(snapshot.legacyRequested());
    }
}
