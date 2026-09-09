package cn.spectra.gallium.glowoutline.sr.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure source analysis for the temporal argument passed to an already-proven
 * {@code FsrScaleVS(gl_Position, ...)} projection call.
 *
 * <p>The input must be Iris's include-expanded, option-preprocessed vertex source. This class
 * deliberately does not evaluate GLSL or decide which Iris program renders a capture. It only
 * classifies every syntactic call that targets {@code gl_Position}, then combines the results of
 * several effective program sources conservatively.</p>
 */
public final class FsrTemporalJitterAnalyzer {

    private static final String FUNCTION_NAME = "FsrScaleVS";
    private static final String POSITION_NAME = "gl_Position";

    private FsrTemporalJitterAnalyzer() {}

    public enum Kind {
        /** No {@code FsrScaleVS(gl_Position, ...)} call was present. */
        NONE,
        /** Every call uses the same simple identifier, to be read as a live custom uniform. */
        UNIFORM,
        /** Every call passes a literal zero vec2. */
        ZERO,
        /** Calls disagree, use an expression, or cannot be parsed safely. */
        AMBIGUOUS
    }

    /**
     * Result for one program or a consensus across programs.
     *
     * @param uniformName non-empty only for {@link Kind#UNIFORM}
     * @param callCount number of relevant call sites contributing to this result
     */
    public record Analysis(Kind kind, String uniformName, int callCount) {
        public Analysis {
            if (kind == null) throw new IllegalArgumentException("kind");
            uniformName = uniformName == null ? "" : uniformName;
            if (kind == Kind.UNIFORM && !isIdentifier(uniformName)) {
                throw new IllegalArgumentException("uniformName");
            }
            if (kind != Kind.UNIFORM && !uniformName.isEmpty()) {
                throw new IllegalArgumentException("uniformName is only valid for UNIFORM");
            }
            if (callCount < 0) throw new IllegalArgumentException("callCount");
        }

        public boolean exactCandidate() {
            return kind == Kind.UNIFORM || kind == Kind.ZERO;
        }

        private static Analysis none() {
            return new Analysis(Kind.NONE, "", 0);
        }

        private static Analysis uniform(String name, int calls) {
            return new Analysis(Kind.UNIFORM, name, calls);
        }

        private static Analysis zero(int calls) {
            return new Analysis(Kind.ZERO, "", calls);
        }

        private static Analysis ambiguous(int calls) {
            return new Analysis(Kind.AMBIGUOUS, "", calls);
        }
    }

    /** Classifies all active call sites in one preprocessed vertex source. */
    public static Analysis analyzeProgram(String preprocessedVertexSource) {
        if (preprocessedVertexSource == null || preprocessedVertexSource.isBlank()) {
            return Analysis.none();
        }

        String source = removeCommentsAndStrings(preprocessedVertexSource);
        List<CallArgument> calls = findPositionCalls(source);
        if (calls.isEmpty()) return Analysis.none();

        Kind agreedKind = null;
        String agreedUniform = "";
        for (CallArgument call : calls) {
            Argument argument = call.malformed()
                    ? Argument.ambiguous()
                    : classifyArgument(call.text());
            if (argument.kind() == Kind.AMBIGUOUS) {
                return Analysis.ambiguous(calls.size());
            }
            if (agreedKind == null) {
                agreedKind = argument.kind();
                agreedUniform = argument.uniformName();
                continue;
            }
            if (agreedKind != argument.kind()
                    || (agreedKind == Kind.UNIFORM
                    && !agreedUniform.equals(argument.uniformName()))) {
                return Analysis.ambiguous(calls.size());
            }
        }

        return agreedKind == Kind.UNIFORM
                ? Analysis.uniform(agreedUniform, calls.size())
                : Analysis.zero(calls.size());
    }

    /**
     * Builds a conservative consensus over effective program sources.
     *
     * <p>A relevant program with no call prevents exact replay when another program has one.
     * Callers should resolve Iris fallback chains before passing sources here, and should omit
     * programs that cannot render the capture class being analyzed (for example, Weather from a
     * WORLD_ITEM consensus).</p>
     */
    public static Analysis consensusSources(Iterable<String> preprocessedVertexSources) {
        if (preprocessedVertexSources == null) return Analysis.none();
        List<Analysis> analyses = new ArrayList<>();
        for (String source : preprocessedVertexSources) {
            analyses.add(analyzeProgram(source));
        }
        return consensus(analyses);
    }

    /** Combines already-analyzed effective programs using the same conservative rules. */
    public static Analysis consensus(Iterable<Analysis> programAnalyses) {
        if (programAnalyses == null) return Analysis.none();

        boolean sawProgram = false;
        boolean sawMissing = false;
        Kind agreedKind = null;
        String agreedUniform = "";
        int callCount = 0;

        for (Analysis analysis : programAnalyses) {
            sawProgram = true;
            if (analysis == null || analysis.kind() == Kind.AMBIGUOUS) {
                return Analysis.ambiguous(callCount);
            }
            callCount += analysis.callCount();
            if (analysis.kind() == Kind.NONE) {
                sawMissing = true;
                continue;
            }
            if (agreedKind == null) {
                agreedKind = analysis.kind();
                agreedUniform = analysis.uniformName();
                continue;
            }
            if (agreedKind != analysis.kind()
                    || (agreedKind == Kind.UNIFORM
                    && !agreedUniform.equals(analysis.uniformName()))) {
                return Analysis.ambiguous(callCount);
            }
        }

        if (!sawProgram || agreedKind == null) return Analysis.none();
        if (sawMissing) return Analysis.ambiguous(callCount);
        return agreedKind == Kind.UNIFORM
                ? Analysis.uniform(agreedUniform, callCount)
                : Analysis.zero(callCount);
    }

    private static List<CallArgument> findPositionCalls(String source) {
        List<CallArgument> calls = new ArrayList<>();
        int cursor = 0;
        while (cursor < source.length()) {
            int identifierEnd = identifierEnd(source, cursor);
            if (identifierEnd <= cursor) {
                cursor++;
                continue;
            }
            String identifier = source.substring(cursor, identifierEnd);
            cursor = identifierEnd;
            if (!FUNCTION_NAME.equals(identifier)) continue;

            int open = skipWhitespace(source, cursor);
            if (open >= source.length() || source.charAt(open) != '(') continue;
            Invocation invocation = parseInvocation(source, open);
            cursor = Math.max(cursor, invocation.endExclusive());

            if (invocation.complete()) {
                if (invocation.arguments().size() < 2
                        || !POSITION_NAME.equals(stripOuterParentheses(
                        invocation.arguments().get(0)))) {
                    continue;
                }
                if (invocation.arguments().size() != 2) {
                    calls.add(new CallArgument("", true));
                } else {
                    calls.add(new CallArgument(invocation.arguments().get(1), false));
                }
            } else if (startsWithPositionArgument(source, open + 1)) {
                calls.add(new CallArgument("", true));
            }
        }
        return calls;
    }

    private static Invocation parseInvocation(String source, int open) {
        List<String> arguments = new ArrayList<>();
        int parenDepth = 1;
        int bracketDepth = 0;
        int braceDepth = 0;
        int argumentStart = open + 1;
        for (int i = open + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            switch (c) {
                case '(' -> parenDepth++;
                case ')' -> {
                    parenDepth--;
                    if (parenDepth == 0) {
                        arguments.add(source.substring(argumentStart, i).trim());
                        return new Invocation(List.copyOf(arguments), i + 1, true);
                    }
                }
                case '[' -> bracketDepth++;
                case ']' -> bracketDepth = Math.max(0, bracketDepth - 1);
                case '{' -> braceDepth++;
                case '}' -> braceDepth = Math.max(0, braceDepth - 1);
                case ',' -> {
                    if (parenDepth == 1 && bracketDepth == 0 && braceDepth == 0) {
                        arguments.add(source.substring(argumentStart, i).trim());
                        argumentStart = i + 1;
                    }
                }
                default -> {
                }
            }
        }
        return new Invocation(List.copyOf(arguments), source.length(), false);
    }

    private static Argument classifyArgument(String expression) {
        String value = stripOuterParentheses(expression);
        if (isIdentifier(value)) return Argument.uniform(value);
        if (isZeroVec2(value)) return Argument.zero();
        return Argument.ambiguous();
    }

    private static boolean isZeroVec2(String expression) {
        int nameEnd = identifierEnd(expression, 0);
        if (nameEnd <= 0 || !"vec2".equals(expression.substring(0, nameEnd))) return false;
        int open = skipWhitespace(expression, nameEnd);
        if (open >= expression.length() || expression.charAt(open) != '(') return false;
        Invocation constructor = parseInvocation(expression, open);
        if (!constructor.complete()
                || !expression.substring(constructor.endExclusive()).isBlank()) {
            return false;
        }
        List<String> components = constructor.arguments();
        if (components.size() != 1 && components.size() != 2) return false;
        for (String component : components) {
            if (!isZeroLiteral(stripOuterParentheses(component))) return false;
        }
        return true;
    }

    private static boolean isZeroLiteral(String expression) {
        String value = expression.trim();
        if (value.isEmpty()) return false;
        while (value.startsWith("+") || value.startsWith("-")) {
            value = value.substring(1).trim();
        }
        if (value.endsWith("f") || value.endsWith("F")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            double number = Double.parseDouble(value);
            return Double.isFinite(number) && number == 0.0d;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String stripOuterParentheses(String expression) {
        String value = expression == null ? "" : expression.trim();
        while (value.length() >= 2 && value.charAt(0) == '(') {
            Invocation outer = parseInvocation(value, 0);
            if (!outer.complete() || outer.endExclusive() != value.length()
                    || outer.arguments().size() != 1) {
                break;
            }
            value = outer.arguments().get(0).trim();
        }
        return value;
    }

    /** Replaces comments and quoted text with whitespace so token boundaries and lines survive. */
    private static String removeCommentsAndStrings(String source) {
        StringBuilder result = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                result.append("  ");
                i += 2;
                while (i < source.length() && source.charAt(i) != '\n') {
                    result.append(' ');
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                result.append("  ");
                i += 2;
                while (i < source.length()) {
                    if (i + 1 < source.length() && source.charAt(i) == '*'
                            && source.charAt(i + 1) == '/') {
                        result.append("  ");
                        i += 2;
                        break;
                    }
                    char comment = source.charAt(i++);
                    result.append(comment == '\n' ? '\n' : ' ');
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                result.append(' ');
                i++;
                boolean escaped = false;
                while (i < source.length()) {
                    char quoted = source.charAt(i++);
                    result.append(quoted == '\n' ? '\n' : ' ');
                    if (escaped) {
                        escaped = false;
                    } else if (quoted == '\\') {
                        escaped = true;
                    } else if (quoted == quote) {
                        break;
                    }
                }
                continue;
            }
            result.append(c);
            i++;
        }
        return result.toString();
    }

    private static boolean startsWithPositionArgument(String source, int start) {
        int cursor = skipWhitespace(source, start);
        int end = identifierEnd(source, cursor);
        return end > cursor && POSITION_NAME.equals(source.substring(cursor, end));
    }

    private static int skipWhitespace(String source, int start) {
        int cursor = Math.max(0, start);
        while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) cursor++;
        return cursor;
    }

    private static int identifierEnd(String source, int start) {
        if (source == null || start < 0 || start >= source.length()
                || !isIdentifierStart(source.charAt(start))) {
            return start;
        }
        int cursor = start + 1;
        while (cursor < source.length() && isIdentifierPart(source.charAt(cursor))) cursor++;
        return cursor;
    }

    private static boolean isIdentifier(String value) {
        if (value == null || value.isEmpty() || !isIdentifierStart(value.charAt(0))) return false;
        for (int i = 1; i < value.length(); i++) {
            if (!isIdentifierPart(value.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isIdentifierStart(char value) {
        return value == '_' || Character.isLetter(value);
    }

    private static boolean isIdentifierPart(char value) {
        return value == '_' || Character.isLetterOrDigit(value);
    }

    private record Invocation(List<String> arguments, int endExclusive, boolean complete) {}

    private record CallArgument(String text, boolean malformed) {}

    private record Argument(Kind kind, String uniformName) {
        private static Argument uniform(String name) {
            return new Argument(Kind.UNIFORM, name);
        }

        private static Argument zero() {
            return new Argument(Kind.ZERO, "");
        }

        private static Argument ambiguous() {
            return new Argument(Kind.AMBIGUOUS, "");
        }
    }
}
