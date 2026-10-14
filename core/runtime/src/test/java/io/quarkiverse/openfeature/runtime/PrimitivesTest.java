package io.quarkiverse.openfeature.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class PrimitivesTest {
    @Test
    void booleanLiterals() {
        assertTrue(Primitives.parseBoolean("true"));
        assertTrue(Primitives.parseBoolean("TRUE"));
        assertTrue(Primitives.parseBoolean("tRUe"));
        assertFalse(Primitives.parseBoolean("false"));
        assertFalse(Primitives.parseBoolean("FALSE"));
        assertFalse(Primitives.parseBoolean("fAlSe"));
    }

    @Test
    void booleanRejectsAnythingElse() {
        // `Boolean.parseBoolean` would answer `false` to every one of these
        assertThrows(IllegalArgumentException.class, () -> Primitives.parseBoolean("yes"));
        assertThrows(IllegalArgumentException.class, () -> Primitives.parseBoolean("on"));
        assertThrows(IllegalArgumentException.class, () -> Primitives.parseBoolean("1"));
        assertThrows(IllegalArgumentException.class, () -> Primitives.parseBoolean("ture"));
        assertThrows(IllegalArgumentException.class, () -> Primitives.parseBoolean(""));
        assertThrows(IllegalArgumentException.class, () -> Primitives.parseBoolean(null));
    }

    @Test
    void booleanErrorMentionsTheValue() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Primitives.parseBoolean("yes"));
        assertEquals("Cannot parse as boolean: yes", e.getMessage());
    }
}
