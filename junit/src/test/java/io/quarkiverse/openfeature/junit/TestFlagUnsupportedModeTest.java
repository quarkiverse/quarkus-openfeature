package io.quarkiverse.openfeature.junit;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.testkit.engine.EventConditions.event;
import static org.junit.platform.testkit.engine.EventConditions.finishedSuccessfully;
import static org.junit.platform.testkit.engine.EventConditions.finishedWithFailure;
import static org.junit.platform.testkit.engine.EventConditions.test;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.instanceOf;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.message;

import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

/**
 * Runs {@link QuarkusUnitTestFlagFixture} through the JUnit engine and asserts that an
 * unreachable provider fails the test instead of letting it run against real flag values.
 */
public class TestFlagUnsupportedModeTest {
    @Test
    void testFlagFailsLoudlyWhenNoProviderIsReachable() {
        Events tests = EngineTestKit.engine("junit-jupiter")
                .selectors(selectClass(QuarkusUnitTestFlagFixture.class))
                .execute()
                .testEvents();

        tests.assertThatEvents()
                .haveExactly(1, event(test("overrideIsNotSilentlyIgnored"),
                        finishedWithFailure(instanceOf(IllegalStateException.class),
                                message(m -> m.contains("no OpenFeature provider")))))
                .haveExactly(1, event(test("unannotatedTestStillRuns"), finishedSuccessfully()));
    }
}
