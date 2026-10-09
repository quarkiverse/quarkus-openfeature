package io.quarkiverse.openfeature.unleash.test;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

import io.quarkus.credentials.CredentialsProvider;

/**
 * A credentials provider that only knows a single credentials name. Asking for
 * any other name yields no credentials, so a test fails if the extension looks
 * up something else than what {@code credentials-provider} was set to.
 */
@Named("test-credentials-provider")
@ApplicationScoped
public class TestCredentialsProvider implements CredentialsProvider {
    public static final String CREDENTIALS_NAME = "unleash-api-key";

    // same value as UnleashTestContainer.API_TOKEN, inlined to keep this bean
    // free of classes that only exist outside the test application
    private static final String API_TOKEN = "default:development.api-token-for-testing";

    @Override
    public Map<String, String> getCredentials(String credentialsProviderName) {
        if (CREDENTIALS_NAME.equals(credentialsProviderName)) {
            return Map.of(CredentialsProvider.PASSWORD_PROPERTY_NAME, API_TOKEN);
        }
        return Map.of();
    }
}
