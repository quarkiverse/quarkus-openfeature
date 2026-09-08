package io.quarkiverse.openfeature.unleash.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class UnleashConsoleUrlTest {
    @Test
    public void apiSuffixIsRemoved() {
        assertEquals("http://localhost:4242", UnleashFeatureProvider.consoleUrl("http://localhost:4242/api"));
    }

    @Test
    public void trailingSlashIsRemoved() {
        assertEquals("http://localhost:4242", UnleashFeatureProvider.consoleUrl("http://localhost:4242/api/"));
        assertEquals("http://localhost:4242", UnleashFeatureProvider.consoleUrl("http://localhost:4242/"));
    }

    @Test
    public void urlWithoutApiSuffixIsUnchanged() {
        assertEquals("http://localhost:4242", UnleashFeatureProvider.consoleUrl("http://localhost:4242"));
    }

    @Test
    public void contextPathIsPreserved() {
        assertEquals("https://unleash.example.com/unleash",
                UnleashFeatureProvider.consoleUrl("https://unleash.example.com/unleash/api"));
    }

    @Test
    public void apiInsideHostNameIsNotRemoved() {
        assertEquals("https://api.example.com", UnleashFeatureProvider.consoleUrl("https://api.example.com"));
    }
}
