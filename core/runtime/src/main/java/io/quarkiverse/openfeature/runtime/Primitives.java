package io.quarkiverse.openfeature.runtime;

/**
 * Utilities for working with values of primitive types.
 */
public class Primitives {
    private Primitives() {
    }

    /**
     * Parses a boolean. Unlike {@link Boolean#parseBoolean(String)}, a value that
     * is neither {@code "true"} nor {@code "false"} leads to an exception, not
     * to a result of {@code false}. This is similar to parsing numbers: if a string
     * cannot be parsed as an {@code int} or {@code double}, an exception results.
     */
    public static boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        } else if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException("Cannot parse as boolean: " + value);
    }
}
