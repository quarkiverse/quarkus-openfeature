End-to-end tests for the flagd provider, based on the official
[flagd-testbed](https://github.com/open-feature/flagd-testbed) Gherkin
scenarios. The tests run a flagd server via Testcontainers and execute
the Cucumber feature files against it.

Everything in `src/test/resources/` is copied from the `flagd-testbed`
repository, tag `v3.8.0`.
