package cn.spectra.gallium.glowoutline.sr.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SrDefinitionLoaderTest {

    private final SrDefinitionParserRegistry parsers = SrDefinitionParserRegistry.builtIn();

    @Test
    void parsesLegacyDefinitionIntoUnifiedModel() {
        var result = parsers.parse("superresolution.json", SrDefinitionFixtures.LEGACY);

        assertTrue(result.loaded(), result.message());
        assertEquals(SrDefinition.Format.LEGACY, result.definition().format());
        SrDefinition.Profile profile = result.definition().profile("0").orElseThrow();
        assertTrue(profile.enabled());
        assertTrue(profile.upscale().enabled());
        assertEquals(SrDefinition.TriggerOrder.AFTER, profile.upscale().trigger().order());
        assertEquals(SrDefinition.RegionValueKind.RENDER_SIZE,
                profile.upscale().inputs().get("color").region().width().kind());
        assertEquals(SrDefinition.RegionValueKind.SCREEN_SIZE,
                profile.upscale().outputs().get("upscaled_color").region().width().kind());
        assertTrue(profile.jitter().enabled());
        assertEquals(SrDefinition.JitterOwner.MOD, profile.jitter().owner());
        assertFalse(profile.jitter().hasCompleteDeclaration());
    }

    @Test
    void registryParsesSchemasOneThroughThree() {
        for (int version = 1; version <= 3; version++) {
            var result = parsers.parse(
                    "superresolution.v" + version + ".json",
                    SrDefinitionFixtures.versioned(version));
            assertTrue(result.loaded(), "v" + version + ": " + result.message());
            assertEquals(version, result.definition().schemaVersion());
            assertEquals(SrDefinition.Format.valueOf("V" + version), result.definition().format());
        }
    }

    @Test
    void versionedOmittedRegionsAndNullOutputRegionDefaultToFullRender() {
        for (int version = 1; version <= 3; version++) {
            String source = SrDefinitionFixtures.versioned(version)
                    .replace(", \"region\": [0, 0, -1, -1]", "")
                    .replace(", \"region\": [0, 0, -2, -2]", "");
            var result = parsers.parse("superresolution.v" + version + ".json", source);

            assertTrue(result.loaded(), "v" + version + ": " + result.message());
            SrDefinition.Upscale upscale =
                    result.definition().profile("*").orElseThrow().upscale();
            assertEquals(SrDefinition.Region.FULL_RENDER,
                    upscale.inputs().get("color").region());
            assertEquals(SrDefinition.Region.FULL_RENDER,
                    upscale.outputs().get("upscaled_color").region());
        }

        assertEquals(SrDefinition.Region.FULL_RENDER,
                new SrDefinition.OutputTexture(true, List.of("colortex0"), null).region());
    }

    @Test
    void parsesTypedJitterSourcesAndLiteralRegions() {
        var result = parsers.parse("superresolution.v3.json", SrDefinitionFixtures.versioned(3));
        SrDefinition.Profile profile = result.definition().profile("overworld").orElseThrow();

        assertTrue(profile.jitter().hasCompleteDeclaration());
        assertEquals(SrDefinition.JitterOwner.SHADERPACK, profile.jitter().owner());
        assertEquals(SrDefinition.SourceKind.UNIFORM, profile.jitter().offset().kind());
        assertEquals(SrDefinition.ValueType.VECTOR2F, profile.jitter().offset().type());
        assertEquals("taaJitter", profile.jitter().offset().reference());
        assertEquals(SrDefinition.SourceKind.CONST, profile.jitter().sequenceLength().kind());
        assertEquals(List.of(8.0), profile.jitter().sequenceLength().constants());

        SrDefinition.Region depth = profile.upscale().inputs().get("depth").region();
        assertEquals(4, depth.x().literal());
        assertEquals(2, depth.y().literal());
        assertEquals(1280, depth.width().literal());
        assertEquals(720, depth.height().literal());
    }

    @Test
    void exactProfileWinsBeforeWildcardFallback() {
        SrDefinition definition = parsers.parse(
                "superresolution.v3.json", SrDefinitionFixtures.versioned(3)).definition();

        assertFalse(definition.profile("-1").orElseThrow().enabled());
        assertTrue(definition.profile("custom").orElseThrow().enabled());
    }

    @Test
    void unknownAndMalformedSchemasFailClosed() {
        var unknown = parsers.parse("superresolution.v4.json",
                "{\"schema_version\":4,\"profiles\":{}}");
        var fractional = parsers.parse("superresolution.v3.json",
                "{\"schema_version\":3.5,\"profiles\":{}}");
        var missing = parsers.parse("superresolution.v3.json", "{\"profiles\":{}}");

        assertEquals(SrDefinitionParserRegistry.ParseStatus.UNSUPPORTED_SCHEMA, unknown.status());
        assertEquals(SrDefinitionParserRegistry.ParseStatus.INVALID_SCHEMA, fractional.status());
        assertEquals(SrDefinitionParserRegistry.ParseStatus.MISSING_SCHEMA, missing.status());
        assertFalse(unknown.loaded());
        assertFalse(fractional.loaded());
        assertFalse(missing.loaded());
    }

    @Test
    void versionedFileNameMustMatchSchemaVersion() {
        var result = parsers.parse(
                "superresolution.v3.json", SrDefinitionFixtures.versioned(2));

        assertEquals(SrDefinitionParserRegistry.ParseStatus.INVALID_SCHEMA, result.status());
        assertFalse(result.loaded());
    }

    @Test
    void disabledLegacyRootDisablesEveryProfileCapability() {
        String disabled = SrDefinitionFixtures.LEGACY
                .replaceFirst("\\\"enabled\\\": true", "\\\"enabled\\\": false");
        var result = parsers.parse("superresolution.json", disabled);

        assertTrue(result.loaded(), result.message());
        SrDefinition.Profile profile = result.definition().profile("0").orElseThrow();
        assertFalse(profile.enabled());
        assertFalse(profile.upscale().enabled());
        assertFalse(profile.jitter().enabled());
    }

    @Test
    void invalidRegionSentinelFailsClosed() {
        String invalid = SrDefinitionFixtures.versioned(3)
                .replace("[0, 0, -1, -1]", "[0, 0, -3, -1]");
        var result = parsers.parse("superresolution.v3.json", invalid);

        assertEquals(SrDefinitionParserRegistry.ParseStatus.INVALID_DEFINITION, result.status());
        assertFalse(result.loaded());
    }

    @Test
    void regionPositionsAndExtentsUseStrictProtocolDomains() {
        for (String invalidRegion : List.of(
                "[-1, 0, 1, 1]", "[0, -2, 1, 1]",
                "[0, 0, 0, 1]", "[0, 0, 1, 0]",
                "[0, 0, -3, 1]", "[0, 0, 1, -3]")) {
            String invalid = SrDefinitionFixtures.versioned(3)
                    .replace("[0, 0, -1, -1]", invalidRegion);
            var result = parsers.parse("superresolution.v3.json", invalid);

            assertEquals(SrDefinitionParserRegistry.ParseStatus.INVALID_DEFINITION,
                    result.status(), invalidRegion);
            assertFalse(result.loaded(), invalidRegion);
        }
    }

    @Test
    void highestExistingCandidateIsSelected(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("superresolution.v1.json"), SrDefinitionFixtures.versioned(1));
        Files.writeString(root.resolve("superresolution.v3.json"), SrDefinitionFixtures.versioned(3));

        SrDefinitionLoader.LoadResult result = new SrDefinitionLoader().load(root);

        assertTrue(result.loaded(), result.message());
        assertEquals("superresolution.v3.json", result.fileName());
        assertEquals(3, result.definition().schemaVersion());
    }

    @Test
    void brokenFirstCandidateDoesNotFallBack(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("superresolution.v3.json"), "{broken");
        Files.writeString(root.resolve("superresolution.v2.json"), SrDefinitionFixtures.versioned(2));

        SrDefinitionLoader.LoadResult result = new SrDefinitionLoader().load(root);

        assertEquals(SrDefinitionLoader.LoadStatus.MALFORMED_JSON, result.status());
        assertEquals("superresolution.v3.json", result.fileName());
        assertFalse(result.loaded());
    }

    @Test
    void macroDefinitionWithoutPreprocessorIsNotExact(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("superresolution.v3.json"), SrDefinitionFixtures.MACRO_WRAPPED);

        SrDefinitionLoader.LoadResult result = new SrDefinitionLoader().load(root);

        assertEquals(SrDefinitionLoader.LoadStatus.PREPROCESSOR_REQUIRED, result.status());
        assertFalse(result.loaded());
        assertFalse(result.exactDefinitionAvailable());
    }

    @Test
    void suppliedPreprocessorCanProduceDefinition(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("superresolution.v3.json"), SrDefinitionFixtures.MACRO_WRAPPED);

        SrDefinitionLoader.LoadResult result = new SrDefinitionLoader().load(
                root, (path, source) -> SrDefinitionFixtures.versioned(3));

        assertTrue(result.loaded(), result.message());
        assertTrue(result.exactDefinitionAvailable());
        assertNotNull(result.definition());
    }

    @Test
    void missingDefinitionIsDistinctFromInvalidDefinition(@TempDir Path root) {
        SrDefinitionLoader.LoadResult result = new SrDefinitionLoader().load(root);
        assertEquals(SrDefinitionLoader.LoadStatus.NOT_FOUND, result.status());
        assertFalse(result.loaded());
    }
}
