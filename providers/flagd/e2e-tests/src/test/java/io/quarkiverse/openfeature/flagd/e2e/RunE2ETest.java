package io.quarkiverse.openfeature.flagd.e2e;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.OBJECT_FACTORY_PROPERTY_NAME;
import static io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectFile;
import org.junit.platform.suite.api.Suite;

@Suite
@IncludeEngines("cucumber")
// `config.feature` is excluded: it tests upstream SDK config plumbing (env vars, option defaults),
// which doesn't apply to Quarkus configuration
@SelectFile("src/test/resources/gherkin/evaluation.feature")
@SelectFile("src/test/resources/gherkin/targeting.feature")
@SelectFile("src/test/resources/gherkin/disabled.feature")
@SelectFile("src/test/resources/gherkin/events.feature")
@SelectFile("src/test/resources/gherkin/connection.feature")
@SelectFile("src/test/resources/gherkin/selector.feature")
@SelectFile("src/test/resources/gherkin/metadata.feature")
@SelectFile("src/test/resources/gherkin/contextEnrichment.feature")
@SelectFile("src/test/resources/gherkin/sync-payload.feature")
@SelectFile("src/test/resources/gherkin/rpc-caching.feature")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "io.quarkiverse.openfeature.flagd.e2e")
@ConfigurationParameter(key = OBJECT_FACTORY_PROPERTY_NAME, value = "io.cucumber.picocontainer.PicoFactory")
@IncludeTags("in-process")
@ExcludeTags({
        "targetURI", // Vert.x gRPC 4.x client API doesn't expose HTTP/2 authority header
        "deprecated", // superseded by newer scenarios
        "fractional-v1", // legacy float-based bucketing, superseded by fractional-v2
        "unixsocket", // flagd-testbed doesn't expose a Unix domain socket
})
public class RunE2ETest {
}
