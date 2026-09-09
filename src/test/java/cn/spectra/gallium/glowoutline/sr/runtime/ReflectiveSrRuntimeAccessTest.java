package cn.spectra.gallium.glowoutline.sr.runtime;

import cn.spectra.gallium.glowoutline.sr.definition.SrDefinition.SourceKind;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectiveSrRuntimeAccessTest {

    @Test
    void missingSrAndIrisClassesAreSafe() {
        ReflectiveSrRuntimeAccess access = new ReflectiveSrRuntimeAccess(new ClassLoader(null) {});

        assertTrue(access.upscaleActive().isEmpty());
        assertTrue(access.extents().isEmpty());
        assertTrue(access.modJitter().isEmpty());
        assertTrue(access.shaderFacingModJitter().isEmpty());
        assertTrue(access.modJitterSequenceLength().isEmpty());
        assertTrue(access.algorithmCode().isEmpty());
        assertTrue(access.shaderValue(SourceKind.UNIFORM, "taaOffset").isEmpty());
        assertTrue(access.shaderValue(SourceKind.VARIABLE, "taaOffset").isEmpty());
    }

    @Test
    void numericAdapterReadsScalarsListsArraysAndVectorAccessors() {
        assertEquals(List.of(2.5),
                ReflectiveSrRuntimeAccess.numericValue(2.5).orElseThrow().components());
        assertEquals(List.of(1.0, 2.0),
                ReflectiveSrRuntimeAccess.numericValue(List.of(1, 2)).orElseThrow().components());
        assertEquals(List.of(3.0, 4.0),
                ReflectiveSrRuntimeAccess.numericValue(new float[]{3.0f, 4.0f})
                        .orElseThrow().components());
        assertEquals(List.of(0.25, -0.5),
                ReflectiveSrRuntimeAccess.numericValue(new TestVector(0.25f, -0.5f))
                        .orElseThrow().components());
    }

    @Test
    void numericAdapterRejectsNonNumericValues() {
        assertTrue(ReflectiveSrRuntimeAccess.numericValue("not numeric").isEmpty());
        assertTrue(ReflectiveSrRuntimeAccess.numericValue(List.of(1, "two")).isEmpty());
    }

    public record TestVector(float x, float y) {
    }
}
