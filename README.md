# Quarkus OpenFeature

[![Version](https://img.shields.io/maven-central/v/io.quarkiverse.openfeature/quarkus-openfeature?logo=apache-maven&style=flat-square)](https://central.sonatype.com/artifact/io.quarkiverse.openfeature/quarkus-openfeature-parent)

A Quarkus extension that integrates the [OpenFeature](https://openfeature.dev/) SDK, providing feature flag evaluation as a CDI service.

## Getting Started

Select an OpenFeature provider and add its extension to your project:

```xml
<dependency>
    <groupId>io.quarkiverse.openfeature</groupId>
    <artifactId>quarkus-openfeature-runtime-config</artifactId>
    <version>${quarkus-openfeature.version}</version>
</dependency>
```

You don't need to explicitly add a dependency on `io.quarkiverse.openfeature:quarkus-openfeature`, it is included transitively.

## Usage

```java
@Inject
Client client;

boolean enabled = client.getBooleanValue("my-feature", false);
```

It is safe to call the `Client` methods on a non-blockable thread (such as Vert.x event loop), because this extension guarantees feature flags are evaluated purely in memory; no I/O occurs.

## Documentation

Full documentation is available at <https://docs.quarkiverse.io/quarkus-openfeature/dev/>.
