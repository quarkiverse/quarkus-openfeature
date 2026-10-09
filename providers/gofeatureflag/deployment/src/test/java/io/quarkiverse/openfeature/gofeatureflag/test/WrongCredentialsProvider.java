package io.quarkiverse.openfeature.gofeatureflag.test;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

import io.quarkus.credentials.CredentialsProvider;

/**
 * A credentials provider that always hands out a token the GO Feature Flag
 * relay proxy rejects. Present so that a test can tell whether it was consulted
 * at all.
 */
@Named("wrong-credentials-provider")
@ApplicationScoped
public class WrongCredentialsProvider implements CredentialsProvider {
    @Override
    public Map<String, String> getCredentials(String credentialsProviderName) {
        return Map.of(CredentialsProvider.PASSWORD_PROPERTY_NAME, "wrong-token");
    }
}
