package cn.spectra.gallium.glowoutline.sr;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SuperResolutionLegacyDepthFormatTest {
    @Test
    void preservesReportedFixedPointPrecision() {
        assertEquals(GL14.GL_DEPTH_COMPONENT24, SuperResolutionLegacyDepthFormat.sizedFormat(
                GL11.GL_DEPTH_COMPONENT, 24, GL30.GL_UNSIGNED_NORMALIZED));
        assertEquals(GL14.GL_DEPTH_COMPONENT32, SuperResolutionLegacyDepthFormat.sizedFormat(
                GL11.GL_DEPTH_COMPONENT, 32, GL30.GL_UNSIGNED_NORMALIZED));
    }

    @Test
    void distinguishesFloatFromNormalizedDepth() {
        assertEquals(GL30.GL_DEPTH_COMPONENT32F, SuperResolutionLegacyDepthFormat.sizedFormat(
                GL11.GL_DEPTH_COMPONENT, 32, GL11.GL_FLOAT));
        assertEquals(GL11.GL_DEPTH_COMPONENT, SuperResolutionLegacyDepthFormat.sizedFormat(
                GL11.GL_DEPTH_COMPONENT, 24, GL11.GL_FLOAT));
    }

    @Test
    void leavesSizedAndUnrepresentableLayoutsUnchanged() {
        assertEquals(GL30.GL_DEPTH_COMPONENT32F, SuperResolutionLegacyDepthFormat.sizedFormat(
                GL30.GL_DEPTH_COMPONENT32F, 24, GL30.GL_UNSIGNED_NORMALIZED));
        assertEquals(GL11.GL_RGBA, SuperResolutionLegacyDepthFormat.sizedFormat(
                GL11.GL_RGBA, 24, GL30.GL_UNSIGNED_NORMALIZED));
        assertEquals(GL11.GL_DEPTH_COMPONENT, SuperResolutionLegacyDepthFormat.sizedFormat(
                GL11.GL_DEPTH_COMPONENT, 16, GL30.GL_UNSIGNED_NORMALIZED));
        assertEquals(GL11.GL_DEPTH_COMPONENT, SuperResolutionLegacyDepthFormat.sizedFormat(
                GL11.GL_DEPTH_COMPONENT, 0, GL11.GL_NONE));
    }
}
