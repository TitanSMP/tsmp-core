# Shared Infrastructure

Shared infrastructure lives in `core` and is platform-independent. Paper and future standalone services can construct `TitanSmpRuntime` with their own configuration source and logging integration. Paper wires player lifecycle events to the shared identity service and EventBus; Velocity does not yet write presence state.

## Configuration

The configuration format is Java properties. Copy the placeholder file for local development:

```shell
cp config/titansmp.example.properties config/titansmp.properties
```

On Windows PowerShell:

```powershell
Copy-Item config/titansmp.example.properties config/titansmp.properties
```

The example contains only dummy values. `config/titansmp.properties` is ignored by Git. Configuration is loaded by `TitanSmpConfigLoader` and includes an explicit server ID, server type, environment, network name and event namespace ID, MariaDB connection and pool size, and Redis connection.

Environment variables override their matching properties. Supported variables are:

| Property | Environment variable |
| --- | --- |
| `server.id` | `TITANSMP_SERVER_ID` |
| `server.type` | `TITANSMP_SERVER_TYPE` |
| `server.environment` | `TITANSMP_ENVIRONMENT` |
| `network.name` | `TITANSMP_NETWORK_NAME` |
| `network.id` | `TITANSMP_NETWORK_ID` |
| `database.host` | `TITANSMP_DATABASE_HOST` |
| `database.port` | `TITANSMP_DATABASE_PORT` |
| `database.name` | `TITANSMP_DATABASE_NAME` |
| `database.username` | `TITANSMP_DATABASE_USERNAME` |
| `database.password` | `TITANSMP_DATABASE_PASSWORD` |
| `database.maximumPoolSize` | `TITANSMP_DATABASE_MAXIMUM_POOL_SIZE` |
| `redis.host` | `TITANSMP_REDIS_HOST` |
| `redis.port` | `TITANSMP_REDIS_PORT` |
| `redis.password` | `TITANSMP_REDIS_PASSWORD` |

The default config path is `config/titansmp.properties`. A host can supply a different path by creating the loader with that path. The properties file may be omitted when all required non-default values are supplied through environment variables. Password fields are redacted from configuration object string representations and are never logged by the core lifecycle.

## Database service

`HikariDatabaseService` owns the single core connection pool. It uses MariaDB Connector/J with a MariaDB JDBC URL and validates a connection during startup. The maximum pool size is configured by `database.maximumPoolSize`. Core repositories and future features should share the `DatabaseService`/`DataSource`; they must not create their own pools or connect from `api`.

TitanSMP uses the single MariaDB database named by the shared configuration. All TitanSMP domains access it through the same core pool and later separate their queries through repositories; feature modules must not create their own databases or pools. This foundation does not create feature tables or add database business logic. It does not configure a separate database for any third-party plugin.

## Migrations

Flyway migrations belong in `core/src/main/resources/db/migration/` and use versioned names such as `V1__create_network_metadata.sql`. Each new change gets a higher version; do not edit a migration after it has been applied to a shared database. Migration scripts must be reviewed and must not perform destructive changes. Flyway `clean` is disabled, baselining and out-of-order migrations are disabled. The current initial migration creates the minimal player identity table. Flyway creates its own schema history table when first run.

When `TitanSmpRuntime.start()` runs, it starts the database pool and applies pending migrations before starting Redis and subscribing the EventBus. Flyway locks the schema history table while applying migrations, so concurrent backend startup against the same database is serialized.

To apply pending migrations locally, create `config/titansmp.properties`, start a local MariaDB using those values, then run:

```shell
./gradlew :core:migrateDatabase
```

The migration task only starts the database pool, applies Flyway migrations, and closes the pool. It does not require Redis. The same task can be run in a production deployment step using production configuration from environment variables. Backend runtime startup also performs migration checks, so running the task first leaves no pending migrations for the servers to apply.

Production migration scripts should be reviewed and deployed with the application version that requires them. Flyway records successful versions in its history table. Do not use `clean` or add destructive scripts. Back up production data according to the deployment's database operations before schema changes.

## Redis service

`LettuceRedisService` owns one shared Lettuce client and one shared command connection. Features reuse the core client rather than creating clients of their own. The event bus opens its dedicated Pub/Sub connection from this same client. The service authenticates when `redis.password` or `TITANSMP_REDIS_PASSWORD` is provided and validates startup with `PING`.

The service exposes Redis commands for shared infrastructure. Redis Pub/Sub messaging and the `titansmp:<network-id>:events:v1` channel are documented in [event-bus.md](event-bus.md).

## Server identity

`api` defines the platform-independent `ServerIdentity`, `ServerType`, `ServerEnvironment`, and `NetworkServer` contract. `core` implements the configured network server. Identity comes from TitanSMP configuration; it is not inferred from hostname, IP address, or the platform.

Supported initial server types are `LOBBY`, `SMP`, and `BEDWARS`; environments are `DEVELOPMENT` and `PRODUCTION`. The configured server ID must be explicit and non-blank.

## Lifecycle and logging

`TitanSmpRuntime` performs startup in this order:

1. Log that TitanSMP is starting.
2. Load configuration.
3. Build and log the configured server identity.
4. Start and validate the MariaDB pool.
5. Run pending migrations.
6. Start and validate Redis.
7. Start the Redis Pub/Sub EventBus subscription for the configured network ID.
8. Enter `READY`.

Shutdown closes the EventBus and its dispatch workers, then Redis, then the MariaDB pool, and enters `STOPPED`. If startup fails, already-started services are closed in reverse order. Runtime state is exposed as `STOPPED`, `STARTING`, or `READY`.

The core logs the server ID, type, environment, network name, and service readiness. It does not log passwords or connection URLs containing credentials. Database JDBC URLs intentionally exclude credentials.

## Secrets and production configuration

Do not commit `config/titansmp.properties`, `.env` files, or real credentials. Use the ignored local properties file for development or provide environment variables through the production runtime's secret mechanism. The tracked example file uses placeholder values only. Configuration and connection objects redact passwords from `toString()` output.

## mc-data-bridge source archive

`mc-data-bridge-main.zip` remains in the repository only as a future integration source. It is not extracted, modified, included as a Gradle source or dependency, or integrated by this infrastructure work. The `modules/data-bridge` module remains an empty structure.
