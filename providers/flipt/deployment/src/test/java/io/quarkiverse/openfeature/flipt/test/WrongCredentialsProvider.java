package io.quarkiverse.openfeature.flipt.test;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

import io.quarkus.credentials.CredentialsProvider;

/**
 * A second credentials provider that always hands out a token the Flipt server
 * rejects. Present so that tests can tell whether the right provider was picked.
 */
@Named("wrong-credentials-provider")
@ApplicationScoped
public class WrongCredentialsProvider implements CredentialsProvider {
    @Override
    public Map<String, String> getCredentials(String credentialsProviderName) {
        return Map.of(CredentialsProvider.PASSWORD_PROPERTY_NAME, "wrong-token");
    }
}
