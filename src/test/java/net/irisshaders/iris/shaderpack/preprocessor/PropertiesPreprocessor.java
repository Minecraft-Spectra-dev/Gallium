package net.irisshaders.iris.shaderpack.preprocessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;

/** Test double that verifies the reflection bridge passes Iris options and exact defines. */
public final class PropertiesPreprocessor {
    private static int invocations;
    private static ShaderPackOptions lastOptions;
    private static Map<String, String> lastDefines = Map.of();

    private PropertiesPreprocessor() {}

    public static String preprocessSource(
            String source, ShaderPackOptions options, Iterable<StringPair> defines) {
        invocations++;
        lastOptions = options;
        Map<String, String> copied = new LinkedHashMap<>();
        for (StringPair define : defines) copied.put(define.key(), define.value());
        lastDefines = Map.copyOf(copied);

        Map<String, String> macros = new LinkedHashMap<>(copied);
        for (String name : options.getOptionSet().getBooleanOptions().keySet()) {
            if (options.getOptionValues().getBooleanValueOrDefault(name)) {
                macros.put(name, "1");
            }
        }
        for (String name : options.getOptionSet().getStringOptions().keySet()) {
            macros.put(name, options.getOptionValues().getStringValueOrDefault(name));
        }

        StringBuilder result = new StringBuilder(source.length());
        List<ConditionalFrame> stack = new ArrayList<>();
        for (String rawLine : logicalLines(source)) {
            String line = rawLine.trim();
            if (line.startsWith("#ifdef ")) {
                push(stack, macros.containsKey(line.substring(7).trim()));
            } else if (line.startsWith("#ifndef ")) {
                push(stack, !macros.containsKey(line.substring(8).trim()));
            } else if (line.startsWith("#if ")) {
                push(stack, matchesCondition(line.substring(4), macros));
            } else if (line.startsWith("#elif ")) {
                ConditionalFrame frame = stack.get(stack.size() - 1);
                boolean selected = frame.parentActive && !frame.branchTaken
                        && matchesCondition(line.substring(6), macros);
                frame.active = selected;
                frame.branchTaken |= selected;
            } else if (line.equals("#else")) {
                ConditionalFrame frame = stack.get(stack.size() - 1);
                frame.active = frame.parentActive && !frame.branchTaken;
                frame.branchTaken |= frame.active;
            } else if (line.equals("#endif")) {
                stack.remove(stack.size() - 1);
            } else if (line.startsWith("#define ")) {
                if (active(stack)) {
                    String[] definition = line.substring(8).trim().split("\\s+", 2);
                    macros.put(definition[0], definition.length == 1 ? "1" : definition[1]);
                }
            } else if (line.startsWith("#undef ")) {
                if (active(stack)) macros.remove(line.substring(7).trim());
            } else if (active(stack)) {
                result.append(rawLine).append('\n');
            }
        }
        return result.toString();
    }

    /** Mirrors C preprocessing's backslash-newline splice before directive evaluation. */
    private static List<String> logicalLines(String source) {
        String[] physicalLines = source.split("\\R", -1);
        List<String> result = new ArrayList<>();
        StringBuilder logical = new StringBuilder();
        for (String physical : physicalLines) {
            String trimmed = physical.stripTrailing();
            boolean continued = trimmed.endsWith("\\");
            if (continued) {
                logical.append(trimmed, 0, trimmed.length() - 1).append(' ');
            } else {
                logical.append(physical);
                result.add(logical.toString());
                logical.setLength(0);
            }
        }
        if (logical.length() > 0) result.add(logical.toString());
        return result;
    }

    private static void push(List<ConditionalFrame> stack, boolean condition) {
        boolean parentActive = active(stack);
        stack.add(new ConditionalFrame(
                parentActive, parentActive && condition, parentActive && condition));
    }

    private static boolean active(List<ConditionalFrame> stack) {
        return stack.isEmpty() || stack.get(stack.size() - 1).active;
    }

    private static boolean matchesCondition(String condition, Map<String, String> macros) {
        for (String alternative : condition.split("\\|\\|")) {
            boolean matches = true;
            for (String term : alternative.split("&&")) {
                matches &= matchesTerm(term.trim(), macros);
            }
            if (matches) return true;
        }
        return false;
    }

    private static boolean matchesTerm(String term, Map<String, String> macros) {
        boolean negate = term.startsWith("!");
        if (negate) term = term.substring(1).trim();
        boolean value;
        if (term.startsWith("defined(")) {
            value = macros.containsKey(term.substring(8, term.length() - 1).trim());
        } else {
            String[] comparison = term.split("\\s*(==|!=|>=|<=|>|<)\\s*", 2);
            if (comparison.length == 1) {
                String resolved = macros.getOrDefault(term, term);
                value = !resolved.isBlank() && !resolved.equals("0")
                        && !resolved.equalsIgnoreCase("false");
            } else {
                String operator = term.substring(
                        comparison[0].length(), term.length() - comparison[1].length()).trim();
                String left = macros.getOrDefault(comparison[0], comparison[0]);
                String right = macros.getOrDefault(comparison[1], comparison[1]);
                value = switch (operator) {
                    case "==" -> left.equals(right);
                    case "!=" -> !left.equals(right);
                    case ">" -> number(left) > number(right);
                    case "<" -> number(left) < number(right);
                    case ">=" -> number(left) >= number(right);
                    case "<=" -> number(left) <= number(right);
                    default -> false;
                };
            }
        }
        return negate != value;
    }

    private static long number(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static final class ConditionalFrame {
        private final boolean parentActive;
        private boolean active;
        private boolean branchTaken;

        private ConditionalFrame(boolean parentActive, boolean active, boolean branchTaken) {
            this.parentActive = parentActive;
            this.active = active;
            this.branchTaken = branchTaken;
        }
    }

    public static void reset() {
        invocations = 0;
        lastOptions = null;
        lastDefines = Map.of();
    }

    public static int invocations() {
        return invocations;
    }

    public static boolean wasInvoked() {
        return invocations > 0;
    }

    public static ShaderPackOptions lastOptions() {
        return lastOptions;
    }

    public static Map<String, String> lastDefines() {
        return lastDefines;
    }
}
