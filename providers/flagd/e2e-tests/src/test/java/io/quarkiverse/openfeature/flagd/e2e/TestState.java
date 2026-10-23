package io.quarkiverse.openfeature.flagd.e2e;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventDetails;
import dev.openfeature.sdk.FlagEvaluationDetails;

public class TestState {
    public Client client;
    public EvaluationContext context;
    public Map<String, String> options = new HashMap<>();
    public String flagName;
    public String flagType;
    public Object flagDefault;
    public FlagEvaluationDetails<?> evaluation;
    public ConcurrentLinkedQueue<ReceivedEvent> events = new ConcurrentLinkedQueue<>();
    // unlike `events`, this is never cleared, so that a scenario can assert that
    // an event was never fired, even after other events were awaited (and cleared)
    public ConcurrentLinkedQueue<ReceivedEvent> allEvents = new ConcurrentLinkedQueue<>();
    public Optional<ReceivedEvent> lastEvent = Optional.empty();

    record ReceivedEvent(String type, EventDetails details) {
    }
}
