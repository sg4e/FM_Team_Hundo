# Server README

- [ ] TODO Replace or update this README with instructions relevant to your application

To start the application in development mode, import it into your IDE and run the `Application` class. 
You can also start the application from the command line by running: 

```bash
./mvnw
```

To build the application in production mode, run:

```bash
./mvnw -Pproduction package
```

For tests:

```bash
./mvnw clean verify -Pintegration-test
```

## Protocol Compatibility Version

GitHub Actions stamps server, FM_Sentinel, and YGOFMPlugin artifacts with `FM_HUNDO_PROTOCOL_VERSION` from `.github/workflows/build.yml`. Bump that opaque value only when the wire protocol between those components becomes incompatible.

Local builds without `FM_HUNDO_PROTOCOL_VERSION` are unstamped. Unstamped components skip protocol-version enforcement so development builds continue to work together.

## Getting Started

The [Getting Started](https://vaadin.com/docs/latest/getting-started) guide will quickly familiarize you with your new
Server implementation. You'll learn how to set up your development environment, understand the project 
structure, and find resources to help you add muscles to your skeleton — transforming it into a fully-featured 
application.
