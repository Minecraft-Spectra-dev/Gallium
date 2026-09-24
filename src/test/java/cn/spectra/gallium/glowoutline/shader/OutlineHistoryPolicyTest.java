package cn.spectra.gallium.glowoutline.shader;

import cn.spectra.gallium.glowoutline.ItemEffectConfig;
import cn.spectra.gallium.glowoutline.ShaderParam;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OutlineHistoryPolicyTest {
    private static ArrayList<ShaderParam> parameters() {
        return new ArrayList<>(List.of(new ShaderParam.Float("Intensity", 1),
                new ShaderParam.Float("PulseSpeed", 2), new ShaderParam.Float("WaveSpeed", -3),
                new ShaderParam.Vec3("InnerColor", 1, .5f, 0),
                new ShaderParam.Vec3("OuterColor", 0, .5f, 1)));
    }

    @Test void accountsForAllHistoryAttachmentsAndRefusesOverflow() {
        assertEquals(254513152L, OutlineHistoryPolicy.storageBytes(3840, 2054));
        assertEquals(Long.MAX_VALUE, OutlineHistoryPolicy.storageBytes(0, 1080));
        assertEquals(Long.MAX_VALUE, OutlineHistoryPolicy.storageBytes(1920, -1));
        assertEquals(Long.MAX_VALUE, OutlineHistoryPolicy.storageBytes(Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test void accountsForSrNativeAlphaAndDepthStorageWithinTheSharedCaptureBudget() {
        assertEquals(388730880L, OutlineHistoryPolicy.srStorageBytes(3840, 2054));
        assertEquals(OutlineHistoryPolicy.storageBytes(1920,1080)+2048L*2048*8,
                OutlineHistoryPolicy.srStorageBytes(1920,1080));
        assertEquals(Long.MAX_VALUE, OutlineHistoryPolicy.srStorageBytes(0,1080));
        assertEquals(Long.MAX_VALUE, OutlineHistoryPolicy.srStorageBytes(Integer.MAX_VALUE,Integer.MAX_VALUE));
    }

    @Test void leavesNegativeAndUnknownEffectsOnTheirOriginalPath() {
        assertTrue(OutlineHistoryPolicy.additiveParameters(new ItemEffectConfig("glow_outline", parameters())));
        var values = parameters();
        values.set(0, new ShaderParam.Float("Intensity", -1));
        assertFalse(OutlineHistoryPolicy.additiveParameters(new ItemEffectConfig("glow_outline", values)));
        for (int color = 3; color < 5; color++) {
            values = parameters();
            values.set(color, new ShaderParam.Vec3(values.get(color).name(), 1, -0.1f, 1));
            assertFalse(OutlineHistoryPolicy.additiveParameters(new ItemEffectConfig("glow_outline", values)));
        }
        values = parameters();
        values.add(new ShaderParam.Float("Custom", 0));
        assertFalse(OutlineHistoryPolicy.additiveParameters(new ItemEffectConfig("glow_outline", values)));
        assertFalse(OutlineHistoryPolicy.additiveParameters(null));
    }
}
