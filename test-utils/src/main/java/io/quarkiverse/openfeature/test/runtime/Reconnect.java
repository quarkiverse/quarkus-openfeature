package io.quarkiverse.openfeature.test.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Reconnects the {@link TcpProxy} <em>before</em> the annotated test method
 * executes. New connections are allowed again.
 * <p>
 * The annotated test method should typically be empty, serving only as a marker
 * for when the reconnection occurs in the ordered test sequence.
 *
 * @see TcpProxyExtension
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Reconnect {
}
