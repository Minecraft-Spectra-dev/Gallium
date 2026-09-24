package cn.spectra.gallium.glowoutline.shader;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AlphaAccumulationTest {
    @Test void doesNotAssumeThatTwoHalfAlphaWritesHaveAlreadySaturatedOnEveryDriver() {
        assertFalse(AlphaAccumulation.saturatesUnorm8(.5f, 2));
        assertTrue(AlphaAccumulation.saturatesUnorm8(.5f, 3));
        assertFalse(AlphaAccumulation.saturatesUnorm8(.25f, 4));
        assertTrue(AlphaAccumulation.saturatesUnorm8(.25f, 5));
        assertFalse(AlphaAccumulation.saturatesUnorm8(0, 1000));
    }

    @Test void everyCertifiedSequenceSaturatesEvenWhenEachConversionRoundsDown() {
        for (int fraction = 1; fraction <= 1024; fraction++) {
            float alpha = fraction / 1024f;
            for (int draws = 1; draws <= 64; draws++) {
                if (!AlphaAccumulation.saturatesUnorm8(alpha, draws)) continue;
                double value = 0;
                for (int i = 0; i < draws; i++) value = Math.floor(Math.min(1, value + alpha) * 255) / 255;
                assertEquals(1, value, "alpha=" + alpha + ", draws=" + draws);
            }
        }
    }
}
