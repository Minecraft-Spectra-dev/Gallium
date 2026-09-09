package net.irisshaders.iris.shaderpack.option;

import java.util.Map;

/** Minimal test double for the reflection-only Iris preprocessor bridge. */
public final class ShaderPackOptions {
    private final Map<String, String> values;
    private final OptionSet optionSet;
    private final OptionValues optionValues;

    public ShaderPackOptions(Map<String, String> values) {
        this.values = values == null ? Map.of() : Map.copyOf(values);
        this.optionSet = new OptionSet(this.values);
        this.optionValues = new OptionValues(this.values);
    }

    public String value(String name) {
        return values.get(name);
    }

    public OptionSet getOptionSet() {
        return optionSet;
    }

    public OptionValues getOptionValues() {
        return optionValues;
    }

    public static final class OptionSet {
        private final Map<String, Object> booleanOptions;
        private final Map<String, Object> stringOptions;

        private OptionSet(Map<String, String> values) {
            this.booleanOptions = values.entrySet().stream()
                    .filter(entry -> entry.getValue().equals("true")
                            || entry.getValue().equals("false"))
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey, ignored -> new Object()));
            this.stringOptions = values.entrySet().stream()
                    .filter(entry -> !booleanOptions.containsKey(entry.getKey()))
                    .collect(
                    java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey, ignored -> new Object()));
        }

        public Map<String, Object> getBooleanOptions() {
            return booleanOptions;
        }

        public Map<String, Object> getStringOptions() {
            return stringOptions;
        }
    }

    public static final class OptionValues {
        private final Map<String, String> values;

        private OptionValues(Map<String, String> values) {
            this.values = values;
        }

        public boolean getBooleanValueOrDefault(String name) {
            return Boolean.parseBoolean(values.getOrDefault(name, "false"));
        }

        public String getStringValueOrDefault(String name) {
            return values.getOrDefault(name, "");
        }
    }
}
