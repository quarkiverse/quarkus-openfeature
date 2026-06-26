package io.quarkiverse.openfeature.flagd.e2e;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.openfeature.sdk.ProviderEvent;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

public class EventSteps {
    private static final int DEFAULT_TIMEOUT_MS = 12_000;

    private final TestState state;

    public EventSteps(TestState state) {
        this.state = state;
    }

    @Given("a {word} event handler")
    public void registerEventHandler(String eventType) {
        state.client.on(mapEventType(eventType), eventDetails -> {
            state.events.add(new TestState.ReceivedEvent(eventType, eventDetails));
        });
    }

    @When("a {word} event was fired")
    public void eventWasFired(String eventType) {
        waitForEvent(eventType, DEFAULT_TIMEOUT_MS);
    }

    @Then("the {word} event handler should have been executed")
    public void eventHandlerShouldBeExecuted(String eventType) {
        waitForEvent(eventType, DEFAULT_TIMEOUT_MS);
    }

    @Then("the {word} event handler should have been executed within {int}ms")
    public void eventHandlerShouldBeExecutedWithin(String eventType, int ms) {
        waitForEvent(eventType, ms);
    }

    @Then("the client should be in {word} state")
    public void clientShouldBeInState(String expectedState) {
        assertThat(state.client.getProviderState().name()).isEqualToIgnoringCase(expectedState);
    }

    private void waitForEvent(String eventType, int timeoutMs) {
        await().atMost(timeoutMs, MILLISECONDS)
                .pollInterval(10, MILLISECONDS)
                .until(() -> state.events.stream().anyMatch(e -> e.type().equals(eventType)));
        state.lastEvent = state.events.stream()
                .filter(e -> e.type().equals(eventType))
                .findFirst();
        state.events.clear();
    }

    private static ProviderEvent mapEventType(String eventType) {
        return switch (eventType) {
            case "ready" -> ProviderEvent.PROVIDER_READY;
            case "error" -> ProviderEvent.PROVIDER_ERROR;
            case "stale" -> ProviderEvent.PROVIDER_STALE;
            case "change" -> ProviderEvent.PROVIDER_CONFIGURATION_CHANGED;
            default -> throw new IllegalArgumentException("Unknown event type: " + eventType);
        };
    }
}
