package io.quarkiverse.openfeature.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.openfeature.sdk.exceptions.TypeMismatchError;

public class FlagOverridesTest {
    private static final double TWO_POW_63 = Math.pow(2, 63);
    private static final double TWO_POW_64 = Math.pow(2, 64);

    private static <T> T evaluate(Object override, Class<T> expectedType) {
        FlagOverrides overrides = new FlagOverrides(Map.of("my-flag", override));
        return overrides.evaluate("my-flag", expectedType).getValue();
    }

    private static void assertRejected(Object override, Class<?> expectedType) {
        assertThrows(TypeMismatchError.class, () -> evaluate(override, expectedType));
    }

    @Test
    public void exactTypeIsReturnedAsIs() {
        assertEquals(10, evaluate(10, Integer.class));
        assertEquals(10L, evaluate(10L, Long.class));
        assertEquals(2.5, evaluate(2.5, Double.class));
    }

    @Test
    public void integerCoercesToLongAndDouble() {
        assertEquals(10L, evaluate(10, Long.class));
        assertEquals(10.0, evaluate(10, Double.class));
    }

    @Test
    public void longCoercesToIntegerAndDouble() {
        assertEquals(10, evaluate(10L, Integer.class));
        assertEquals(10.0, evaluate(10L, Double.class));
    }

    @Test
    public void doubleCoercesToIntegerAndLong() {
        assertEquals(10, evaluate(10.0, Integer.class));
        assertEquals(10L, evaluate(10.0, Long.class));
    }

    @Test
    public void nonIntegralDoubleIsRejected() {
        assertRejected(2.5, Integer.class);
        assertRejected(2.5, Long.class);
    }

    @Test
    public void nonFiniteDoubleIsRejected() {
        assertRejected(Double.NaN, Integer.class);
        assertRejected(Double.NaN, Long.class);
        assertRejected(Double.POSITIVE_INFINITY, Integer.class);
        assertRejected(Double.POSITIVE_INFINITY, Long.class);
        assertRejected(Double.NEGATIVE_INFINITY, Integer.class);
        assertRejected(Double.NEGATIVE_INFINITY, Long.class);
    }

    @Test
    public void unrelatedTypesAreRejected() {
        assertRejected("10", Integer.class);
        assertRejected(10, Boolean.class);
    }

    // Widening is always exact, so the bounds are accepted unconditionally.

    @Test
    public void integerBoundsCoerceToLong() {
        assertEquals((long) Integer.MAX_VALUE, evaluate(Integer.MAX_VALUE, Long.class));
        assertEquals((long) Integer.MIN_VALUE, evaluate(Integer.MIN_VALUE, Long.class));
    }

    @Test
    public void integerBoundsCoerceToDouble() {
        assertEquals((double) Integer.MAX_VALUE, evaluate(Integer.MAX_VALUE, Double.class));
        assertEquals((double) Integer.MIN_VALUE, evaluate(Integer.MIN_VALUE, Double.class));
    }

    @Test
    public void longBoundsCoerceToDouble() {
        // long to double is deliberately allowed even where it loses precision:
        // Long.MAX_VALUE is 2^63 - 1, but the nearest double is 2^63
        assertEquals(TWO_POW_63, evaluate(Long.MAX_VALUE, Double.class));
        // Long.MIN_VALUE is -2^63 and therefore exact
        assertEquals(-TWO_POW_63, evaluate(Long.MIN_VALUE, Double.class));
    }

    // Narrowing to int is accepted exactly on the int bounds and rejected one step out.

    @Test
    public void longAtIntegerBoundsCoercesToInteger() {
        assertEquals(Integer.MAX_VALUE, evaluate((long) Integer.MAX_VALUE, Integer.class));
        assertEquals(Integer.MIN_VALUE, evaluate((long) Integer.MIN_VALUE, Integer.class));
    }

    @Test
    public void longOutsideIntegerBoundsIsRejected() {
        assertRejected(Integer.MAX_VALUE + 1L, Integer.class);
        assertRejected(Integer.MIN_VALUE - 1L, Integer.class);
        assertRejected(Long.MAX_VALUE, Integer.class);
        assertRejected(Long.MIN_VALUE, Integer.class);
    }

    @Test
    public void doubleAtIntegerBoundsCoercesToInteger() {
        assertEquals(Integer.MAX_VALUE, evaluate((double) Integer.MAX_VALUE, Integer.class));
        assertEquals(Integer.MIN_VALUE, evaluate((double) Integer.MIN_VALUE, Integer.class));
    }

    @Test
    public void doubleOutsideIntegerBoundsIsRejected() {
        // every int is exactly representable as a double, so these are the adjacent integers
        assertRejected(Integer.MAX_VALUE + 1.0, Integer.class);
        assertRejected(Integer.MIN_VALUE - 1.0, Integer.class);
    }

    // Narrowing to long is asymmetric, because 2^63 - 1 is not representable as a double.

    @Test
    public void doubleAtLongLowerBoundCoercesToLong() {
        // Long.MIN_VALUE is -2^63 and therefore exactly representable
        assertEquals(-TWO_POW_63, (double) Long.MIN_VALUE);
        assertEquals(Long.MIN_VALUE, evaluate(-TWO_POW_63, Long.class));
    }

    @Test
    public void largestDoubleBelowLongUpperBoundCoercesToLong() {
        // just below 2^63, consecutive doubles are 2^10 apart
        double largest = Math.nextDown(TWO_POW_63);
        assertEquals(TWO_POW_63 - Math.pow(2, 10), largest);
        // Long.MAX_VALUE is 2^63 - 1, so 2^63 - 2^10 is Long.MAX_VALUE - 1023
        assertEquals(Long.MAX_VALUE - 1023, evaluate(largest, Long.class));
    }

    @Test
    public void doubleAtLongUpperBoundIsRejected() {
        // Long.MAX_VALUE cannot be expressed as a double at all, it rounds up to 2^63;
        // accepting it back would silently return Long.MAX_VALUE for an input one larger
        assertEquals(TWO_POW_63, (double) Long.MAX_VALUE);
        assertRejected(TWO_POW_63, Long.class);
    }

    @Test
    public void doubleOutsideLongBoundsIsRejected() {
        assertRejected(Math.nextDown(-TWO_POW_63), Long.class);
        assertRejected(TWO_POW_64, Long.class);
        assertRejected(-TWO_POW_64, Long.class);
    }
}
