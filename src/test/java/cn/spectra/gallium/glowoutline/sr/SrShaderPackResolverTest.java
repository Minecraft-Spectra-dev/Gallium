package cn.spectra.gallium.glowoutline.sr;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.preprocessor.PropertiesPreprocessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SrShaderPackResolverTest {

    @TempDir Path tempDir;

    @Test
    void copiesTheExactIrisConstructorEnvironment() {
        Map<String, String> result = SrShaderPackResolver.tryCopyEnvironmentDefines(List.of(
                        new Pair("MC_VERSION", "12111"),
                        new Pair("IRIS_FEATURE_SSBO", "")))
                .orElseThrow();

        assertEquals(Map.of(
                "MC_VERSION", "12111",
                "IRIS_FEATURE_SSBO", ""), result);
    }

    @Test
    void malformedEnvironmentDoesNotMasqueradeAsComplete() {
        assertTrue(SrShaderPackResolver.tryCopyEnvironmentDefines(
                List.of(new Object())).isEmpty());
    }

    @Test
    void copiesEffectivePackOptionsForTheSrPreprocessor() {
        FakeIrisPack pack = new FakeIrisPack(new ShaderPackOptions(Map.of(
                "FSR2_SCALE", "3",
                "MOTION_BLUR", "true",
                "DISABLED_FEATURE", "false")));

        Map<String, String> result =
                SrShaderPackResolver.tryCopyShaderPackOptionMacros(pack).orElseThrow();

        assertEquals("3", result.get("FSR2_SCALE"));
        assertEquals("", result.get("MOTION_BLUR"));
        assertFalse(result.containsKey("DISABLED_FEATURE"));
    }

    @Test
    void profileLookupFallsBackToOldIrisPrivateDimensionMap() {
        Object dimension = new Object();
        assertEquals("-1", SrShaderPackResolver.profileKeyForDimension(
                new PrivateDimensionPack(Map.of(dimension, "world-1")), dimension));
        assertEquals("1", SrShaderPackResolver.profileKeyForDimension(
                new PublicDimensionPack(Map.of(dimension, "world1")), dimension));
    }

    @Test
    void noSrMacroValidationPreservesKnownAbsenceAndLocalDirectiveOrder() {
        assertDoesNotThrow(() -> SrShaderPackResolver.requireNoUnavailableSrMacros("""
                #ifdef SR_INSTALLED
                {broken
                #else
                {}
                #endif
                #define SR_LOCAL 1
                #if SR_LOCAL
                {}
                #endif
                #undef SR_LOCAL
                """, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> SrShaderPackResolver.requireNoUnavailableSrMacros(
                        "#if SR_LOCAL\n{}\n#endif", Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> SrShaderPackResolver.requireNoUnavailableSrMacros(
                        "#if SR_SCREEN_WIDTH > 0\n{}\n#endif", Set.of()));
    }

    public record Pair(String key, String value) {
    }

    private static final class PrivateDimensionPack {
        @SuppressWarnings("unused")
        private final Map<Object, String> dimensionMap;

        private PrivateDimensionPack(Map<Object, String> dimensionMap) {
            this.dimensionMap = dimensionMap;
        }
    }

    public static final class PublicDimensionPack {
        private final Map<Object, String> dimensionMap;

        private PublicDimensionPack(Map<Object, String> dimensionMap) {
            this.dimensionMap = dimensionMap;
        }

        public Map<Object, String> getDimensionMap() {
            return dimensionMap;
        }
    }

    @Test
    void resolvesRootAndNestedZipShaderDirectories() throws Exception {
        Path zip = tempDir.resolve("pack.zip");
        URI uri = URI.create("jar:" + zip.toUri());
        try (FileSystem fs = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            Path rootShaders = fs.getPath("shaders");
            Files.createDirectories(rootShaders);
            assertEquals(fs.getPath("/").toAbsolutePath().normalize(),
                    SrShaderPackResolver.packRootForShaders(rootShaders).orElseThrow());

            Path nestedShaders = fs.getPath("Pack Name", "shaders");
            Files.createDirectories(nestedShaders);
            assertEquals(fs.getPath("/Pack Name").toAbsolutePath().normalize(),
                    SrShaderPackResolver.packRootForShaders(nestedShaders).orElseThrow());
        }
    }

    @Test
    void noSrJarUsesIrisOptionsAndCapturedDefinesForMacroJson() throws Exception {
        String source = """
                #if FSR2_SCALE == 3 && MC_VERSION == 12111
                {"schema_version":3,"profiles":{"*":{"enabled":true,"upscale":{"enabled":false},"jitter":{"enabled":false,"source":"mod"}}}}
                #else
                {broken
                #endif
                """;
        Files.writeString(tempDir.resolve("superresolution.v3.json"), source);
        ShaderPackOptions options = new ShaderPackOptions(Map.of("FSR2_SCALE", "3"));
        FakeIrisPack pack = new FakeIrisPack(options);
        PropertiesPreprocessor.reset();

        SrShaderPackResolver.Session session = SrShaderPackResolver.load(
                tempDir, pack, Map.of("MC_VERSION", "12111")).orElseThrow();

        assertEquals(1, PropertiesPreprocessor.invocations());
        assertSame(options, PropertiesPreprocessor.lastOptions());
        assertEquals("12111", PropertiesPreprocessor.lastDefines().get("MC_VERSION"));
        assertFalse(PropertiesPreprocessor.lastDefines().keySet().stream()
                .anyMatch(name -> name.startsWith("SR_")));
        assertTrue(session.definition().profile("missing").orElseThrow().enabled());
    }

    @Test
    void noSrJarRejectsRuntimeOnlySrMacrosInsteadOfTreatingThemAsZero() throws Exception {
        Files.writeString(tempDir.resolve("superresolution.v3.json"), """
                #if SR_SCREEN_WIDTH > 0
                {"schema_version":3,"profiles":{}}
                #else
                {"schema_version":3,"profiles":{}}
                #endif
                """);
        PropertiesPreprocessor.reset();

        assertTrue(SrShaderPackResolver.load(
                tempDir,
                new FakeIrisPack(new ShaderPackOptions(Map.of())),
                Map.of("MC_VERSION", "12111")).isEmpty());
        assertEquals(1, PropertiesPreprocessor.invocations());
    }

    @Test
    void activeGenericAliasToUnavailableSrMacroFailsClosed() throws Exception {
        Files.writeString(tempDir.resolve("superresolution.v3.json"), """
                #define WIDTH SR_SCREEN_WIDTH
                #if WIDTH > 0
                {"schema_version":3,"profiles":{}}
                #else
                {"schema_version":3,"profiles":{}}
                #endif
                """);
        PropertiesPreprocessor.reset();

        assertTrue(SrShaderPackResolver.load(
                tempDir,
                new FakeIrisPack(new ShaderPackOptions(Map.of())),
                Map.of("MC_VERSION", "12111")).isEmpty());
        assertEquals(1, PropertiesPreprocessor.invocations());
    }

    @Test
    void activeFunctionLikeAliasToUnavailableSrMacroFailsClosed() throws Exception {
        Files.writeString(tempDir.resolve("superresolution.v3.json"), """
                #define WIDTH(x) (SR_SCREEN_WIDTH + (x))
                {"schema_version":3,"profiles":{}}
                """);
        PropertiesPreprocessor.reset();

        assertTrue(SrShaderPackResolver.load(
                tempDir,
                new FakeIrisPack(new ShaderPackOptions(Map.of())),
                Map.of("MC_VERSION", "12111")).isEmpty());
        assertEquals(1, PropertiesPreprocessor.invocations());
    }

    @Test
    void logicalConditionalContinuationIsNotSplitByProbeMarkers() throws Exception {
        Files.writeString(tempDir.resolve("superresolution.v3.json"), """
                #if PACK_MODE == 1 && \\
                    MC_VERSION == 12111
                {"schema_version":3,"profiles":{"*":{"enabled":true}}}
                #else
                {broken
                #endif
                """);
        PropertiesPreprocessor.reset();

        assertTrue(SrShaderPackResolver.load(
                tempDir,
                new FakeIrisPack(new ShaderPackOptions(Map.of("PACK_MODE", "1"))),
                Map.of("MC_VERSION", "12111")).isPresent());
        assertEquals(1, PropertiesPreprocessor.invocations());
    }

    @Test
    void noSrValidationUsesIrisSelectedNestedBranchesAndLocalDefines() throws Exception {
        Files.writeString(tempDir.resolve("superresolution.v3.json"), """
                #define SR_LOCAL 1
                #ifdef SR_INSTALLED
                  #if SR_SCREEN_WIDTH > 0
                  {broken
                  #endif
                #elif PACK_MODE == 1
                  #if SR_LOCAL
                  {"schema_version":3,"profiles":{"*":{"enabled":true}}}
                  #else
                  {broken
                  #endif
                #else
                  #if SR_RENDER_WIDTH > 0
                  {broken
                  #endif
                #endif
                """);
        PropertiesPreprocessor.reset();

        assertTrue(SrShaderPackResolver.load(
                tempDir,
                new FakeIrisPack(new ShaderPackOptions(Map.of("PACK_MODE", "1"))),
                Map.of("MC_VERSION", "12111")).isPresent());
        assertEquals(1, PropertiesPreprocessor.invocations());
    }

    @Test
    void evaluatedElifStillRejectsAnUnavailableDynamicSrMacro() throws Exception {
        Files.writeString(tempDir.resolve("superresolution.v3.json"), """
                #if PACK_MODE == 0
                {broken
                #elif SR_SCREEN_WIDTH > 0
                {broken
                #else
                {"schema_version":3,"profiles":{}}
                #endif
                """);
        PropertiesPreprocessor.reset();

        assertTrue(SrShaderPackResolver.load(
                tempDir,
                new FakeIrisPack(new ShaderPackOptions(Map.of("PACK_MODE", "1"))),
                Map.of("MC_VERSION", "12111")).isEmpty());
        assertEquals(1, PropertiesPreprocessor.invocations());
    }

    @Test
    void unvisitedElifDoesNotRejectItsUnavailableDynamicSrMacro() throws Exception {
        Files.writeString(tempDir.resolve("superresolution.v3.json"), """
                #if PACK_MODE == 1
                {"schema_version":3,"profiles":{}}
                #elif SR_SCREEN_WIDTH > 0
                {broken
                #else
                {broken
                #endif
                """);
        PropertiesPreprocessor.reset();

        assertTrue(SrShaderPackResolver.load(
                tempDir,
                new FakeIrisPack(new ShaderPackOptions(Map.of("PACK_MODE", "1"))),
                Map.of("MC_VERSION", "12111")).isPresent());
        assertEquals(1, PropertiesPreprocessor.invocations());
    }

    @Test
    void packDefinedSrNamedOptionRemainsAvailableWithoutTheSrMod() throws Exception {
        Files.writeString(tempDir.resolve("superresolution.v3.json"), """
                #if SR_PACK_MODE == 1
                {"schema_version":3,"profiles":{"*":{"enabled":true}}}
                #else
                {broken
                #endif
                """);
        PropertiesPreprocessor.reset();

        assertTrue(SrShaderPackResolver.load(
                tempDir,
                new FakeIrisPack(new ShaderPackOptions(Map.of("SR_PACK_MODE", "1"))),
                Map.of("MC_VERSION", "12111")).isPresent());
        assertTrue(PropertiesPreprocessor.wasInvoked());
    }

    @Test
    void srNamedUniformInsideJsonStringIsNotMistakenForAMacro() throws Exception {
        Files.writeString(tempDir.resolve("superresolution.v3.json"), """
                {"schema_version":3,"profiles":{"*":{"enabled":false,
                "description":"SR_SCREEN_WIDTH"}}}
                """);
        PropertiesPreprocessor.reset();

        assertTrue(SrShaderPackResolver.load(
                tempDir,
                new FakeIrisPack(new ShaderPackOptions(Map.of())),
                Map.of("MC_VERSION", "12111")).isPresent());
        assertTrue(PropertiesPreprocessor.wasInvoked());
    }

    public static final class FakeIrisPack {
        private final ShaderPackOptions options;

        public FakeIrisPack(ShaderPackOptions options) {
            this.options = options;
        }

        public ShaderPackOptions getShaderPackOptions() {
            return options;
        }
    }
}
