package io.quarkiverse.openfeature.runtimeconfig.test;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.openfeature.sdk.Client;
import io.quarkus.test.QuarkusUnitTest;
import io.smallrye.common.annotation.Identifier;

/**
 * A named domain exists only because {@code quarkus.openfeature."<domain>".provider} is configured
 * for it. Provider-specific configuration under the same domain key belongs to a different
 * configuration mapping and does not declare the domain, so no {@code Client} is produced.
 */
public class RuntimeConfigUndeclaredDomainTest {
    @RegisterExtension
    static final QuarkusUnitTest test = new QuarkusUnitTest()
            .overrideConfigKey("quarkus.openfeature.runtime.default-flag", "from-default")
            .overrideConfigKey("quarkus.openfeature.\"custom\".runtime.custom-flag", "from-custom")
            .assertException(e -> {
                assertThat(e).hasMessageContaining("Unsatisfied dependency")
                        .hasMessageContaining("@Identifier(\"custom\")");
            });

    @Inject
    @Identifier("custom")
    Client customClient;

    @Test
    void trigger() {
    }
}
