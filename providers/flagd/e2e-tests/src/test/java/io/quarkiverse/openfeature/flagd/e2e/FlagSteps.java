package io.quarkiverse.openfeature.flagd.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.Value;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

public class FlagSteps {
    private final TestState state;

    public FlagSteps(TestState state) {
        this.state = state;
    }

    @Given("a {}-flag with key {string} and a default value {string}")
    public void givenAFlag(String type, String name, String defaultValue) {
        state.flagType = type;
        state.flagName = name;
        state.flagDefault = convert(defaultValue, type);
    }

    @When("the flag was evaluated with details")
    public void evaluateWithDetails() {
        state.evaluation = switch (state.flagType) {
            case "Boolean" -> state.client.getBooleanDetails(state.flagName, (Boolean) state.flagDefault, state.context);
            case "String" -> state.client.getStringDetails(state.flagName, (String) state.flagDefault, state.context);
            case "Integer" -> state.client.getIntegerDetails(state.flagName, (Integer) state.flagDefault, state.context);
            case "Float" -> state.client.getDoubleDetails(state.flagName, (Double) state.flagDefault, state.context);
            case "Object" -> state.client.getObjectDetails(state.flagName, (Value) state.flagDefault, state.context);
            default -> throw new AssertionError("Unknown flag type: " + state.flagType);
        };
    }

    @Then("the resolved details value should be {string}")
    public void resolvedValueShouldBe(String expected) {
        assertThat(state.evaluation.getValue()).isEqualTo(convert(expected, state.flagType));
    }

    @Then("the reason should be {string}")
    public void reasonShouldBe(String reason) {
        assertThat(state.evaluation.getReason()).isEqualTo(reason);
    }

    @Then("the variant should be {string}")
    public void variantShouldBe(String variant) {
        assertThat(state.evaluation.getVariant()).isEqualTo(variant);
    }

    @Then("the error-code should be {string}")
    public void errorCodeShouldBe(String errorCode) {
        if (errorCode.isEmpty()) {
            assertThat(state.evaluation.getErrorCode()).isNull();
        } else {
            assertThat(state.evaluation.getErrorCode()).isEqualTo(ErrorCode.valueOf(errorCode));
        }
    }

    @Then("the flag should be part of the event payload")
    public void flagShouldBePartOfEventPayload() {
        TestState.ReceivedEvent event = state.lastEvent.orElseThrow(AssertionError::new);
        assertThat(event.details().getFlagsChanged()).contains(state.flagName);
    }

    @Then("the resolved metadata is empty")
    public void resolvedMetadataIsEmpty() {
        assertThat(state.evaluation.getFlagMetadata().isEmpty()).isTrue();
    }

    @Then("the resolved metadata should contain")
    public void resolvedMetadataShouldContain(DataTable dataTable) {
        ImmutableMetadata flagMetadata = state.evaluation.getFlagMetadata();
        for (Map<String, String> row : dataTable.asMaps(String.class, String.class)) {
            String key = row.get("key");
            String value = row.get("value");
            String type = row.get("metadata_type");
            switch (type) {
                case "Boolean" -> assertThat(flagMetadata.getBoolean(key)).isEqualTo(Boolean.parseBoolean(value));
                case "String" -> assertThat(flagMetadata.getString(key)).isEqualTo(value);
                case "Integer" -> assertThat(flagMetadata.getInteger(key)).isEqualTo(Integer.parseInt(value));
                case "Float" -> assertThat(flagMetadata.getDouble(key)).isEqualTo(Double.parseDouble(value));
                default -> throw new AssertionError("Unknown metadata type: " + type);
            }
        }
    }

    private static Object convert(String value, String type) {
        return switch (type) {
            case "Boolean" -> Boolean.parseBoolean(value);
            case "String" -> value;
            case "Integer" -> Integer.parseInt(value);
            case "Float" -> Double.parseDouble(value);
            case "Object" -> new Value();
            default -> throw new AssertionError("Unknown value type: " + type);
        };
    }
}
