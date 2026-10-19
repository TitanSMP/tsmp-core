# TitanSMP

TitanSMP is a Kotlin monorepo for a modular Minecraft network built on Paper and Velocity. Shared player identity, the network EnderChest, and the Data Bridge player-state transfer path are implemented on the common infrastructure.

## Foundation and platform modules

- `api` – public, platform-independent interfaces, DTOs, enums, and other abstractions. It has no Paper or Velocity dependencies.
- `core` – shared implementation and central network logic. It may depend on `api`.
- `paper` – Paper-specific implementation only. It may depend on `api` and `core`.
- `velocity` – Velocity-specific implementation only. It may depend on `api` and `core`.

Paper and Velocity APIs are declared as `compileOnly` dependencies in their respective modules. MariaDB is the durable player-state source of truth; Redis provides shared event delivery and transient transfer coordination.

Feature modules and standalone services are listed in the [module architecture documentation](docs/architecture.md). The documentation index is in [docs/README.md](docs/README.md).

## Requirements

- Java 25
- Gradle Wrapper (Gradle 9.8.1)

## Build

```shell
./gradlew build
```

On Windows, run `gradlew.bat build`.

Kotlin source files belong under `src/main/kotlin/de/titansmp/<module>/`. Public contracts belong in `api`; platform-specific implementations stay in `paper` or `velocity`.
