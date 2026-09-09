package cn.spectra.gallium.glowoutline.sr.definition;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Selects and loads one SR shader-pack definition without silently changing candidates. */
public final class SrDefinitionLoader {

    public static final List<String> CANDIDATE_NAMES = List.of(
            "superresolution.v3.json",
            "superresolution.v2.json",
            "superresolution.v1.json",
            "superresolution.json");

    private static final Pattern MACRO_DIRECTIVE = Pattern.compile(
            "(?m)^\\s*#\\s*(?:if|ifdef|ifndef|elif|else|endif|define|undef)\\b");

    private final SrDefinitionParserRegistry parsers;

    public SrDefinitionLoader() {
        this(SrDefinitionParserRegistry.builtIn());
    }

    public SrDefinitionLoader(SrDefinitionParserRegistry parsers) {
        if (parsers == null) throw new IllegalArgumentException("parsers");
        this.parsers = parsers;
    }

    public LoadResult load(Path packRoot) {
        return load(packRoot, null);
    }

    /**
     * Loads only the first existing candidate. If that file is malformed, unsupported, or cannot
     * be preprocessed, the result is a failure; an older candidate is never tried.
     */
    public LoadResult load(Path packRoot, TextPreprocessor preprocessor) {
        if (packRoot == null) {
            return LoadResult.failure(LoadStatus.IO_ERROR, "", "pack root is null");
        }

        Path selected = null;
        for (String candidate : CANDIDATE_NAMES) {
            Path path = packRoot.resolve(candidate);
            if (Files.isRegularFile(path)) {
                selected = path;
                break;
            }
        }
        if (selected == null) return LoadResult.notFound();

        final String original;
        try {
            original = Files.readString(selected, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return LoadResult.failure(LoadStatus.IO_ERROR,
                    selected.getFileName().toString(), message(t));
        }

        String source = original;
        if (preprocessor != null) {
            try {
                source = preprocessor.process(selected, source);
            } catch (Throwable t) {
                return LoadResult.failure(LoadStatus.PREPROCESSOR_ERROR,
                        selected.getFileName().toString(), message(t));
            }
            if (source == null || hasMacroDirectives(source)) {
                return LoadResult.failure(LoadStatus.PREPROCESSOR_ERROR,
                        selected.getFileName().toString(),
                        "preprocessor left unresolved macro directives");
            }
        } else if (hasMacroDirectives(source)) {
            return LoadResult.failure(LoadStatus.PREPROCESSOR_REQUIRED,
                    selected.getFileName().toString(),
                    "definition contains macro directives but no preprocessor is available");
        }

        SrDefinitionParserRegistry.ParseResult parsed = parsers.parse(
                selected.getFileName().toString(), source);
        if (!parsed.loaded()) {
            return LoadResult.failure(mapStatus(parsed.status()),
                    selected.getFileName().toString(), parsed.message());
        }
        return LoadResult.loaded(selected.getFileName().toString(), parsed.definition());
    }

    public static boolean hasMacroDirectives(String source) {
        return source != null && MACRO_DIRECTIVE.matcher(source).find();
    }

    @FunctionalInterface
    public interface TextPreprocessor {
        String process(Path definitionPath, String source) throws Exception;
    }

    public enum LoadStatus {
        LOADED,
        NOT_FOUND,
        IO_ERROR,
        PREPROCESSOR_REQUIRED,
        PREPROCESSOR_ERROR,
        MALFORMED_JSON,
        MISSING_SCHEMA,
        INVALID_SCHEMA,
        UNSUPPORTED_SCHEMA,
        INVALID_DEFINITION
    }

    public record LoadResult(LoadStatus status,
                             String fileName,
                             SrDefinition definition,
                             String message) {
        public LoadResult {
            if (status == null) throw new IllegalArgumentException("status");
            fileName = fileName == null ? "" : fileName;
            message = message == null ? "" : message;
        }

        public static LoadResult loaded(String fileName, SrDefinition definition) {
            return new LoadResult(LoadStatus.LOADED, fileName, definition, "");
        }

        public static LoadResult notFound() {
            return new LoadResult(LoadStatus.NOT_FOUND, "", null, "");
        }

        public static LoadResult failure(LoadStatus status, String fileName, String message) {
            if (status == LoadStatus.LOADED || status == LoadStatus.NOT_FOUND) {
                throw new IllegalArgumentException("failure status");
            }
            return new LoadResult(status, fileName, null, message);
        }

        public boolean loaded() {
            return status == LoadStatus.LOADED && definition != null;
        }

        /** A failed/macro-unresolved load must never be treated as an exact projection source. */
        public boolean exactDefinitionAvailable() {
            return loaded();
        }
    }

    private static LoadStatus mapStatus(SrDefinitionParserRegistry.ParseStatus status) {
        return switch (status) {
            case LOADED -> LoadStatus.LOADED;
            case MALFORMED_JSON -> LoadStatus.MALFORMED_JSON;
            case MISSING_SCHEMA -> LoadStatus.MISSING_SCHEMA;
            case INVALID_SCHEMA -> LoadStatus.INVALID_SCHEMA;
            case UNSUPPORTED_SCHEMA -> LoadStatus.UNSUPPORTED_SCHEMA;
            case INVALID_DEFINITION -> LoadStatus.INVALID_DEFINITION;
        };
    }

    private static String message(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName() : message;
    }
}
