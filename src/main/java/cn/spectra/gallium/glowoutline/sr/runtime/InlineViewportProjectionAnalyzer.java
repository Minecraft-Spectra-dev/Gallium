package cn.spectra.gallium.glowoutline.sr.runtime;

import java.util.Optional;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only proof of a homogeneous viewport transform, including pure helper calls. */
final class InlineViewportProjectionAnalyzer {
    private static final String ID = "[A-Za-z_][A-Za-z0-9_]*";
    private static final String POSITION = "gl_Position\\s*\\.\\s*";
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{");
    private static final Pattern INITIAL = Pattern.compile("\\bgl_Position\\s*=\\s*[^;{}]+;");
    private static final Pattern SCALE = Pattern.compile("\\b" + POSITION + "xy\\s*=\\s*"
            + POSITION + "xy\\s*\\*\\s*(" + ID + ")\\s*\\+\\s*\\(\\s*\\1\\s*-\\s*1(?:\\.0+)?\\s*\\)"
            + "\\s*\\*\\s*" + POSITION + "w\\s*;");
    private static final Pattern JITTER = Pattern.compile("\\b" + POSITION + "xy\\s*\\+=\\s*("
            + ID + ")\\s*\\*\\s*" + POSITION + "w\\s*;");
    private static final String ZERO = "0(?:\\.0+)?";
    private static final String ZERO_VECTOR = "vec2\\s*\\(\\s*" + ZERO
            + "(?:\\s*,\\s*" + ZERO + ")?\\s*\\)";
    private static final Pattern NDC_TRANSFORM = Pattern.compile("\\b" + POSITION + "xy\\s*/=\\s*"
            + POSITION + "w\\s*;\\s*" + POSITION + "xy\\s*=\\s*" + POSITION
            + "xy\\s*\\*\\s*(" + ID + ")\\s*\\+\\s*\\1\\s*-\\s*1(?:\\.0+)?\\s*;"
            + "(?:\\s*" + POSITION + "xy\\s*\\+=\\s*(" + ZERO_VECTOR + "|" + ID + ")"
            + "(\\s*\\*\\s*\\1)?\\s*;)?\\s*" + POSITION + "xy\\s*\\*=\\s*" + POSITION + "w\\s*;");
    private static final Pattern BOUNDS = Pattern.compile(
            "\\bif\\s*\\(\\s*any\\s*\\(\\s*greaterThan(?:Equal)?\\s*\\(\\s*gl_FragCoord\\s*\\.\\s*xy"
                    + "\\s*,\\s*(" + ID + ")\\s*\\)\\s*\\)\\s*\\)\\s*"
                    + "(?:\\{\\s*discard\\s*;\\s*(?:return\\s*;\\s*)?\\}|discard\\s*;)");

    private InlineViewportProjectionAnalyzer() {}

    record Analysis(String scaleUniform, String extentUniform, String jitterUniform,
                    boolean jitterBeforeScale, boolean exactJitter) {
        Analysis withoutTemporalJitter() {
            return new Analysis(scaleUniform, extentUniform, "", false, false);
        }

        boolean sameViewport(Analysis other) {
            return other != null && scaleUniform.equals(other.scaleUniform)
                    && extentUniform.equals(other.extentUniform);
        }

        boolean sameTemporalTransform(Analysis other) {
            return other != null && exactJitter && other.exactJitter
                    && jitterUniform.equals(other.jitterUniform)
                    && jitterBeforeScale == other.jitterBeforeScale;
        }
    }

    static Optional<Analysis> analyze(String vertexSource, String fragmentSource) {
        String vertex = code(vertexSource), fragment = code(fragmentSource);
        if (vertex == null || fragment == null) return Optional.empty();
        Body vertexMain = main(vertex), fragmentMain = main(fragment);
        if (vertexMain == null || fragmentMain == null) return Optional.empty();
        // Expand only the analysis copy. The actual Iris source and shader-pack files remain
        // untouched. Unknown/ambiguous/stateful calls remain references and fail the proof.
        String vertexBody = normalizeNdc(new Helpers(vertex).positionCalls(vertexMain.text(), new HashSet<>()));
        String fragmentBody = new Helpers(fragment).viewportCalls(fragmentMain.text(), new HashSet<>());

        Match scale = single(SCALE, vertexBody);
        Match bounds = single(BOUNDS, fragmentBody);
        if (scale == null || bounds == null || !standalone(vertexBody, scale.start())
                || !standalone(fragmentBody, bounds.start())) return Optional.empty();
        // An early return or an extra write/escaping reference can bypass/change the transform.
        String scaleUniform = scale.group(), extentUniform = bounds.group();
        if (!uniformVec2(vertex, vertexBody, scaleUniform)
                || !uniformVec2(fragment, fragmentBody, extentUniform)) return Optional.empty();

        Matcher jitterMatcher = JITTER.matcher(vertexBody);
        Match jitter = null;
        if (jitterMatcher.find()) {
            jitter = match(jitterMatcher);
            if (jitterMatcher.find() || !standalone(vertexBody, jitter.start())
                    || !uniformVec2(vertex, vertexBody, jitter.group())) return Optional.empty();
        }
        int firstTransformStart = jitter == null ? scale.start() : Math.min(scale.start(), jitter.start());
        Match initial = lastInitialBefore(vertexBody, firstTransformStart);
        if (initial == null || !standalone(vertexBody, initial.start())) return Optional.empty();
        int lastTransformEnd = jitter == null ? scale.end() : Math.max(scale.end(), jitter.end());
        if (Pattern.compile("\\breturn\\b").matcher(vertexBody.substring(0, lastTransformEnd)).find()) {
            return Optional.empty();
        }

        StringBuilder remaining = new StringBuilder(vertexBody);
        // Earlier position setup may involve several matrix operations. The final assignment
        // must dominate the verified viewport suffix; opaque setup never proves exact jitter.
        erase(remaining, new Match(0, initial.end(), ""));
        erase(remaining, scale);
        if (jitter != null) erase(remaining, jitter);
        Pattern positionReference = Pattern.compile("\\bgl_Position\\b");
        if (positionReference.matcher(withoutPositionCopies(remaining.toString())).find()
                || positionReference.matcher(vertex.substring(0, vertexMain.start())).find()
                || positionReference.matcher(vertex.substring(vertexMain.end())).find()) return Optional.empty();

        // Initial assignments can already contain an opaque jitter/projection matrix (including
        // one produced on the GPU in an SSBO). No visible += jitter does NOT prove zero jitter.
        // Only the known vanilla initial projection establishes the temporal baseline here.
        String initialStatement = vertexBody.substring(initial.start(), initial.end()).replaceAll("\\s+", "");
        boolean knownInitial = (initialStatement.equals("gl_Position=ftransform();")
                || initialStatement.equals("gl_Position=gl_ProjectionMatrix*gl_ModelViewMatrix*gl_Vertex;"))
                && !Pattern.compile("\\bvec4\\s+ftransform\\s*\\(").matcher(vertex).find();
        return Optional.of(new Analysis(scaleUniform, extentUniform,
                jitter == null ? "" : jitter.group(), jitter != null && jitter.start() < scale.start(), knownInitial));
    }

    /** Canonicalize the algebraically equivalent divide/scale/re-homogenize sequence. */
    private static String normalizeNdc(String body) {
        Matcher matcher = NDC_TRANSFORM.matcher(body);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String scale = matcher.group(1), jitter = matcher.group(2);
            String scaleStatement = "gl_Position.xy = gl_Position.xy * " + scale
                    + " + (" + scale + " - 1.0) * gl_Position.w;";
            String jitterStatement = jitter == null || jitter.matches(ZERO_VECTOR) ? ""
                    : "gl_Position.xy += " + jitter + " * gl_Position.w;";
            String replacement = matcher.group(3) == null ? scaleStatement + jitterStatement
                    : jitterStatement + scaleStatement;
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static Match lastInitialBefore(String body, int end) {
        Matcher initial = INITIAL.matcher(body);
        Match result = null;
        while (initial.find() && initial.end() <= end) result = match(initial);
        return result;
    }

    /** Pure arithmetic copies (e.g. portal coordinates) read position without changing it. */
    private static String withoutPositionCopies(String body) {
        Matcher copies = Pattern.compile("\\b(" + ID + ")(?:\\.[xyzwrgba]{1,4})?\\s*=\\s*"
                + "([A-Za-z_0-9.\\s+*/-]+);").matcher(body);
        StringBuilder result = new StringBuilder();
        while (copies.find()) {
            String expression = copies.group(2);
            boolean readOnly = !copies.group(1).equals("gl_Position")
                    && !expression.contains("++") && !expression.contains("--");
            copies.appendReplacement(result, Matcher.quoteReplacement(readOnly ? "" : copies.group()));
        }
        copies.appendTail(result);
        return result.toString();
    }

    /** A bounded, name-independent resolver for side-effect-free viewport helper functions. */
    private static final class Helpers {
        private static final Pattern DEFINITION = Pattern.compile(
                "\\b(void|bool)\\s+(" + ID + ")\\s*\\(([^()]*)\\)\\s*\\{");
        private static final Pattern POSITION_CALL = Pattern.compile(
                "\\b(" + ID + ")\\s*\\(\\s*gl_Position\\b");
        private static final Pattern VIEWPORT_CALL = Pattern.compile(
                "\\b(" + ID + ")\\s*\\(\\s*gl_FragCoord\\s*\\.\\s*xy\\s*\\)");
        private static final Pattern VIEWPORT_EXPRESSION = Pattern.compile(
                "any\\s*\\(\\s*greaterThan(?:Equal)?\\s*\\(\\s*gl_FragCoord\\s*\\.\\s*xy"
                        + "\\s*,\\s*" + ID + "\\s*\\)\\s*\\)");
        private final Map<String, Helper> functions = new HashMap<>();
        private final Set<String> ambiguous = new HashSet<>();
        private int expansions;

        private record Helper(String type, String parameters, String body) {}

        Helpers(String source) {
            Matcher definitions = DEFINITION.matcher(source);
            while (definitions.find()) {
                String name = definitions.group(2);
                int end = closingBrace(source, definitions.end() - 1);
                if (end < 0) continue;
                Helper helper = new Helper(definitions.group(1), definitions.group(3),
                        source.substring(definitions.end(), end));
                if (functions.putIfAbsent(name, helper) != null) ambiguous.add(name);
            }
        }

        String positionCalls(String code, Set<String> chain) {
            Matcher calls = POSITION_CALL.matcher(code);
            StringBuilder result = new StringBuilder();
            int copied = 0;
            while (calls.find()) {
                int opening = code.indexOf('(', calls.start());
                int closing = closingDelimiter(code, opening, '(', ')');
                if (closing < 0) continue;
                int end = closing + 1;
                while (end < code.length() && Character.isWhitespace(code.charAt(end))) end++;
                if (end == code.length() || code.charAt(end) != ';') continue;
                String expanded = positionBody(calls.group(1), code.substring(opening + 1, closing), chain);
                if (expanded == null) continue;
                result.append(code, copied, calls.start()).append(expanded);
                copied = end + 1;
                calls.region(copied, code.length());
            }
            result.append(code, copied, code.length());
            return result.toString();
        }

        private String positionBody(String name, String arguments, Set<String> chain) {
            Helper helper = functions.get(name);
            if (!enter(name, helper, "void", chain)) return null;
            try {
                String[] parameters = helper.parameters().split(",");
                List<String> args = arguments(arguments);
                if (args == null || parameters.length != args.size()) return null;
                String body = helper.body();
                Set<String> names = new HashSet<>();
                // Substitute simultaneously so a caller's identifier cannot be captured by
                // another formal parameter (for example f(gl_Position, factor, scale)).
                Map<String, String> substitutions = new HashMap<>();
                for (int i = 0; i < parameters.length; i++) {
                    String prefix = i == 0 ? "inout\\s+vec4" : "(?:in\\s+)?(?:vec2|float)";
                    Matcher parameter = Pattern.compile("\\s*" + prefix + "\\s+(" + ID + ")\\s*")
                            .matcher(parameters[i]);
                    if (!parameter.matches() || !names.add(parameter.group(1))
                            || !(args.get(i).matches(ID) || (i > 0 && args.get(i).matches(ZERO_VECTOR)))) return null;
                    substitutions.put(parameter.group(1), args.get(i));
                }
                body = substitute(body, substitutions);
                body = normalizeNdc(positionCalls(body, chain));
                // No branches, locals, side effects, extra component writes or unknown calls
                // may hide in a helper that we are about to treat as an inline transform.
                String other = JITTER.matcher(SCALE.matcher(body).replaceAll("")).replaceAll("");
                return !body.isBlank() && other.isBlank() ? body : null;
            } finally { chain.remove(name); }
        }

        private static List<String> arguments(String source) {
            List<String> arguments = new ArrayList<>();
            int depth = 0, start = 0;
            for (int i = 0; i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == '(') depth++;
                if (c == ')' && --depth < 0) return null;
                if (c == ',' && depth == 0) { arguments.add(source.substring(start, i).trim()); start = i + 1; }
            }
            if (depth != 0) return null;
            arguments.add(source.substring(start).trim());
            return arguments;
        }

        String viewportCalls(String code, Set<String> chain) {
            Matcher calls = VIEWPORT_CALL.matcher(code);
            StringBuilder result = new StringBuilder();
            while (calls.find()) {
                String expanded = viewportExpression(calls.group(1), chain);
                calls.appendReplacement(result, Matcher.quoteReplacement(expanded == null ? calls.group() : expanded));
            }
            calls.appendTail(result);
            return result.toString();
        }

        private String viewportExpression(String name, Set<String> chain) {
            Helper helper = functions.get(name);
            if (!enter(name, helper, "bool", chain)) return null;
            try {
                Matcher parameter = Pattern.compile("\\s*(?:in\\s+)?vec2\\s+(" + ID + ")\\s*")
                        .matcher(helper.parameters());
                Matcher returned = Pattern.compile("\\s*return\\s+([^;{}]+);\\s*").matcher(helper.body());
                if (!parameter.matches() || !returned.matches()) return null;
                String expression = substitute(returned.group(1), Map.of(parameter.group(1), "gl_FragCoord.xy"));
                expression = viewportCalls(expression, chain).trim();
                return VIEWPORT_EXPRESSION.matcher(expression).matches() ? expression : null;
            } finally { chain.remove(name); }
        }

        private boolean enter(String name, Helper helper, String type, Set<String> chain) {
            if (helper == null || !helper.type().equals(type) || ambiguous.contains(name)
                    || helper.body().length() > 4096 || chain.size() >= 8 || ++expansions > 64) return false;
            return chain.add(name);
        }

        private static String substitute(String body, Map<String, String> substitutions) {
            Matcher identifiers = Pattern.compile(ID).matcher(body);
            StringBuilder result = new StringBuilder();
            while (identifiers.find()) identifiers.appendReplacement(result,
                    Matcher.quoteReplacement(substitutions.getOrDefault(identifiers.group(), identifiers.group())));
            identifiers.appendTail(result);
            return result.toString();
        }
    }

    private record Body(String text, int start, int end) {}
    private record Match(int start, int end, String group) {}

    private static Match single(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) return null;
        Match result = match(matcher);
        return matcher.find() ? null : result;
    }

    private static Match match(Matcher matcher) {
        return new Match(matcher.start(), matcher.end(), matcher.groupCount() == 0 ? "" : matcher.group(1));
    }

    private static void erase(StringBuilder text, Match match) {
        for (int i = match.start(); i < match.end(); i++) text.setCharAt(i, ' ');
    }

    private static boolean uniformVec2(String source, String body, String name) {
        if (!Pattern.compile("\\buniform\\s+vec2\\s+" + Pattern.quote(name) + "\\s*;")
                .matcher(source).find()) return false;
        return !Pattern.compile("\\b(?:[biud]?vec[234]|float|double|int|uint|bool)\\s+"
                + Pattern.quote(name) + "\\b").matcher(body).find();
    }

    /** Reject unresolved directives: only Iris may choose the effective option/macro branch. */
    private static String code(String source) {
        if (source == null) return null;
        // JCPP preserves long runs of blank lines from disabled branches. A multiline ^\s*#
        // regex retries the same whitespace suffix at every line and can stall the render
        // thread for minutes. Consume each character once instead.
        StringBuilder code = new StringBuilder(source.length());
        boolean lineStart = true;
        for (int i = 0; i < source.length();) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                i += 2;
                while (i < source.length() && source.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                code.append(' '); // A comment separates tokens; it must not join identifiers.
                i += 2;
                boolean closed = false;
                while (i < source.length()) {
                    if (i + 1 < source.length() && source.charAt(i) == '*' && source.charAt(i + 1) == '/') {
                        i += 2;
                        closed = true;
                        break;
                    }
                    if (source.charAt(i++) == '\n') { code.append('\n'); lineStart = true; }
                }
                if (!closed) return null;
                continue;
            }
            if (c == '#') {
                if (!lineStart) return null;
                i++;
                while (i < source.length() && horizontalSpace(source.charAt(i))) i++;
                int start = i;
                while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) i++;
                String directive = source.substring(start, i);
                if (!directive.equals("version") && !directive.equals("extension") && !directive.equals("line")) {
                    return null;
                }
                while (i < source.length() && source.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '"' || c == '\'') return null;
            code.append(c);
            if (c == '\n') lineStart = true;
            else if (!horizontalSpace(c)) lineStart = false;
            i++;
        }
        return code.toString();
    }

    private static boolean horizontalSpace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\f';
    }

    private static Body main(String source) {
        Matcher matcher = MAIN.matcher(source);
        if (!matcher.find()) return null;
        int opening = matcher.end() - 1;
        if (matcher.find()) return null;
        int end = closingBrace(source, opening);
        return end < 0 ? null : new Body(source.substring(opening + 1, end), opening + 1, end);
    }

    private static int closingBrace(String source, int opening) {
        return closingDelimiter(source, opening, '{', '}');
    }

    private static int closingDelimiter(String source, int opening, char open, char close) {
        int depth = 1;
        for (int i = opening + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == open) depth++;
            if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** A matched statement must be unconditional at main's outer scope, not an if/loop body. */
    private static boolean standalone(String body, int position) {
        int depth = 0, parentheses = 0, boundary = 0;
        for (int i = 0; i < position; i++) {
            char c = body.charAt(i);
            if (c == '(') parentheses++;
            if (c == ')') parentheses--;
            if (c == '{') depth++;
            if (c == '}') {
                depth--;
                if (depth == 0) boundary = i + 1;
            }
            if (c == ';' && depth == 0 && parentheses == 0) boundary = i + 1;
        }
        return depth == 0 && parentheses == 0 && body.substring(boundary, position).isBlank();
    }
}
