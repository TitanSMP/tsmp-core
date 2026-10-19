# Redis Event Bus

## Architecture and contract

The `api` module defines `NetworkEvent`, `EventMetadata`, `EventType`, `EventEnvelope`, `EventBus`, listeners, and removable subscriptions. These types do not expose Redis, Lettuce, or JSON library classes. Core owns JSON encoding, Redis Pub/Sub, and asynchronous dispatch. Paper and Velocity can depend on the same API and use the `EventBus` provided by the runtime.

The Redis channel is `titansmp:<network-id>:events:v1`. `network.id` / `TITANSMP_NETWORK_ID` selects the namespace, defaulting to a normalized form of `network.name` (for example, `TitanSMP` becomes `titansmp`). IDs are restricted to lowercase letters, digits, dots, underscores, and hyphens. Different namespaces do not subscribe to each other's channels.

Each JSON envelope contains `schemaVersion`, `eventId`, `eventType`, `timestamp`, `sourceServerId`, and `payload`. The current schema version is `1`; timestamps use ISO-8601 UTC. Event IDs are UUIDs. Event source is the configured `ServerIdentity.id`, never a host or address. Core uses Jackson Kotlin and Java Time modules internally; no Java native serialization or `toString()` encoding is used. Unknown versions and event types are rejected and logged without dispatch.

The initial registered payloads are `PlayerConnected`, `PlayerDisconnected`, and `PlayerServerChanged`. UUID identifies a player; username is additional mutable data. Unknown event types are not decoded. New event families must add an explicit event type and codec mapping before publication/subscription.

## Publish, subscribe, and threading

Publishing returns a `CompletionStage<Unit>` that completes after Redis acknowledges the publish. Redis I/O is asynchronous through a dedicated Lettuce Pub/Sub connection. Subscriptions are filtered locally by event type and may have multiple listeners. Listener execution uses a core-owned worker pool with a bounded queue, never the Paper main thread. Each handler exception is logged and isolated from other listeners. If the worker queue is saturated, the rejected dispatch is logged. Closing a subscription removes that listener. Closing the bus stops message delivery, closes its Pub/Sub connection, and shuts down its handler pool.

Paper performs player database work on its existing single worker. It publishes presence only after the database operation succeeds. Redis publish failures are logged without secrets and complete the returned stage exceptionally; Paper logs those failures too. No event is intentionally treated as successfully published when Redis has not acknowledged it. Redis is a required dependency for `READY`, as it already is in the runtime startup sequence; failure to connect or subscribe fails startup and rolls back resources.

## Delivery and idempotency

Redis Pub/Sub is transient, at-most-once messaging: disconnected subscribers do not receive retained messages, and publication acknowledgment does not mean a consumer processed the event. Consumers must make handlers idempotent using `eventId` and the relevant domain state. The bus does not implement global deduplication, retries, durable queues, replay, or delivery guarantees. A future requirement for durable delivery should add an explicit transport design rather than silently assuming Pub/Sub persistence.

## Lifecycle

Startup order is configuration and server identity, database, migrations, Redis, EventBus subscription, then `READY`. Shutdown order is EventBus and its listeners/executor, Redis, database, then `STOPPED`. The bus is closed before the Redis client it uses. Failed subscription is a startup failure, not a silent event drop.

## Player presence ownership

Paper join creates/loads the UUID identity, sets it online with the configured server ID, and then publishes `PlayerConnected` or `PlayerServerChanged` based on the previously persisted presence. On quit, the conditional owner check must successfully transition offline before Paper publishes `PlayerDisconnected`. A stale quit from a former backend therefore emits no disconnect event and cannot erase the new server's state.

Velocity can use the shared event bus, but does not write player presence in this step. Proxy/backend presence ownership still needs a shared authoritative transition protocol to define ordering and prevent competing writes. No new ownership rule is inferred here.

## Future use

The generic event foundation is intended to carry future Moderation, Chat, Economy, Data Bridge, Friends, Clans, and Discord events. Those domains, Discord chat, Web API integration, and gameplay features are not implemented in this change.
