# TitanSMP

TitanSMP is a Kotlin monorepo for a modular Minecraft network built on Paper and Velocity. This repository currently contains the project and module structure only; no network features have been implemented yet.

## Modules

- `api` – public, platform-independent interfaces, DTOs, enums, and other abstractions. It has no Paper or Velocity dependencies.
- `core` – shared implementation and central network logic. It may depend on `api`.
- `paper` – Paper-specific implementation only. It may depend on `api` and `core`.
- `velocity` – Velocity-specific implementation only. It may depend on `api` and `core`.

Paper and Velocity APIs are declared as `compileOnly` dependencies in their respective modules. MariaDB and Redis dependencies will be considered when the corresponding infrastructure is needed.

## Requirements

- Java 25
- Gradle Wrapper (Gradle 9.8.1)

## Build

```shell
./gradlew build
```

On Windows, run `gradlew.bat build`.

Kotlin source files belong under `src/main/kotlin/de/titansmp/<module>/`. Public contracts belong in `api`; platform-specific implementations stay in `paper` or `velocity`.
