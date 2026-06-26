package io.quarkiverse.openfeature.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

import dev.openfeature.sdk.FlagValueType;

/**
 * Overrides a feature flag for the duration of a test method (or all methods in a test class).
 * <p>
 * The {@link #value()} string is converted to the target type based on the {@link #type()}:
 * <ul>
 * <li>{@link FlagValueType#BOOLEAN BOOLEAN}: parsed via {@link Boolean#parseBoolean(String)}</li>
 * <li>{@link FlagValueType#INTEGER INTEGER}: parsed via {@link Integer#parseInt(String)}</li>
 * <li>{@link FlagValueType#DOUBLE DOUBLE}: parsed via {@link Double#parseDouble(String)}</li>
 * <li>{@link FlagValueType#STRING STRING}: used as-is</li>
 * <li>{@link FlagValueType#OBJECT OBJECT}: not supported, throws {@link IllegalArgumentException}</li>
 * </ul>
 * <p>
 * Method-level annotations override class-level annotations for the same flag name and domain.
 * <p>
 * Flags that are not overridden fall through to the real provider's evaluation.
 * After the test, all overrides are cleared so subsequent tests see the real flag values.
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(TestFlag.List.class)
@ExtendWith(OpenFeatureTestExtension.class)
public @interface TestFlag {
    /**
     * The domain to which this flag belongs. An empty string means the default domain.
     */
    String domain() default "";

    /**
     * The flag key.
     */
    String key();

    /**
     * The flag value as a string. Converted according to {@link #type()}.
     */
    String value();

    /**
     * The value type. Defaults to {@link FlagValueType#BOOLEAN}.
     * The type of {@link FlagValueType#OBJECT} is not supported.
     */
    FlagValueType type() default FlagValueType.BOOLEAN;

    @Target({ ElementType.METHOD, ElementType.TYPE })
    @Retention(RetentionPolicy.RUNTIME)
    @ExtendWith(OpenFeatureTestExtension.class)
    @interface List {
        TestFlag[] value();
    }
}
