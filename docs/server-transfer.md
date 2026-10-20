# Network Server Transfer and Queues

## Scope

The server-transfer feature provides `/join <server-type>`, `/lobby`, `/hub`, network-wide queues,
LuckPerms-based queue priorities, dynamic backend discovery, capacity-aware selection, and a
reusable transfer abstraction. It reuses the existing coordinated Data Bridge transfer protocol and
the existing Redis EventBus.

It does **not** implement `/tpa`, `/tpahere`, `/home`, or `/rtp`, and it does **not** add a second
server-transfer protocol.

## Module boundaries

- `api` contains the platform-independent contracts: `ServerTransferService`, the result/status
  types, `QueuePriorityResolver`-neutral queue DTOs used by callers, and the versioned queue events.
  It has no Paper, Velocity, LuckPerms, MariaDB, Redis, or JDA dependency.
- `modules/server-transfer` contains the queue ordering model, the deterministic least-populated
  selection, the coordinator (`DefaultServerTransferService`), the `QueueStore` abstraction with an
  in-memory implementation for tests and a Redis implementation for production, and the isolated
  LuckPerms priority adapter.
- `velocity` owns backend discovery, the connection gateway, the `/join`, `/lobby`, and `/hub`
  commands, and the scheduler that drains queues. The gateway requests an ordinary Velocity backend
  connection, so the existing `ServerPreConnectEvent` transfer coordinator still runs the full
  Data Bridge protocol.
- `paper` owns only the ActionBar presentation of queue positions. It subscribes to the existing
  Redis EventBus and never talks to Redis or MariaDB directly.
- `core` owns configuration loading and exposes the shared network ID and Redis service to the
  platform wiring. No new Redis client, connection pool, or Pub/Sub implementation is introduced.

## Server types and backend mapping

Server types and backends are fully configuration-driven:

```properties
server-transfer.enabled=false
server-transfer.default-priority=0
server-transfer.queue.actionbar-interval-ms=1000
server-transfer.queue.reservation-timeout-ms=5000
server-transfer.queue.processing-interval-ms=1000

server-transfer.aliases.lobby=lobby
server-transfer.aliases.hub=lobby

server-transfer.types.lobby.enabled=true
server-transfer.types.lobby.max-players=100
server-transfer.types.smp.enabled=true
server-transfer.types.smp.max-players=50

server-transfer.backends.lobby-01.type=lobby
server-transfer.backends.lobby-02.type=lobby
server-transfer.backends.smp-01.type=smp
server-transfer.backends.smp-02.type=smp
```

The backend names above are examples only. **Backend names are never hardcoded in production code.**
Velocity's registered servers are filtered to those that have a configured `server-transfer.backends.<name>.type`
mapping; every other registered backend is invisible to this feature.

Configuration validation rejects unknown server types, disabled server types referenced by a
backend or alias, duplicate backend names (case-insensitively), duplicate type names, missing
capacities, non-positive capacities, invalid priorities, invalid alias targets, and
`server-transfer.enabled=true` without at least one server type.

## `/join`, `/lobby`, and `/hub`

- `/join <server-type>` accepts configured server types only. `/join lobby-01` is **not** a request
  to connect directly to a backend named `lobby-01`; it is rejected as an unknown server type. The
  error message does not enumerate registered backends.
- `/lobby` and `/hub` resolve through `server-transfer.aliases.*` to a configured server type. The
  alias target is configuration, so the lobby server type can be renamed without code changes.
- If the player's current server already belongs to the requested server type, no transfer is
  performed. The mapping is resolved from configuration, never from a literal backend name.
- If the request cannot be satisfied, the player is queued for that server type.

## Native Velocity `/server`

TitanSMP does **not** register a `/server` command, and it does **not** define a
`titansmp.server.admin` permission. Velocity's native `/server` command remains authoritative for
its own `velocity.command.server` permission. Native `/server` authorization is completely separate
from queue priorities.

When a player uses native `/server`, Velocity's own permission handling decides whether the
connection happens. If it succeeds, the TitanSMP queue listener observes the resulting server-change
event and removes any stale queue entry or reservation for that player. TitanSMP never interferes
with that authorization decision.

## Backend selection

For a server-type request the service evaluates Velocity's registered backends and selects the
least populated one that:

1. exists and is registered,
2. maps to the requested server type,
3. is available (pinged successfully),
4. accepts players,
5. has capacity (`playerCount < maxPlayers`).

Ties are broken deterministically by backend name. No backend name is special-cased.

## Queue behavior

Queues are per server type and live entirely in Redis; there is no MariaDB table for queue entries.

- A player cannot exist in two queues. Joining another server type first removes the old entry and
  publishes a `server.queue.left` event with reason `MOVED`.
- A duplicate join for the same type shows the existing position instead of creating a second entry.
- Ordering is **priority descending, then FIFO**. A new player joins at the end of their own
  priority group; existing players are never reordered. Redis sorted-set members encode the inverted
  priority and a monotonic sequence so lexicographic ordering yields exactly this order.
- Queue position is one-based and counts all entries of that type, including entries that are
  currently reserved for a transfer. `/join` messages and the ActionBar report
  `Queue position: <position> / <size>`.

## Queue priorities

LuckPerms is the only permission source. Priorities use:

```text
titansmp.server.priority.<number>
```

Higher numbers win; if multiple numeric priorities are granted, the highest valid one is used.
Explicitly denied or malformed nodes are ignored. Without any valid node the configured
`server-transfer.default-priority` (default `0`) applies. No rank names are hardcoded.

The resolver is isolated behind `QueuePriorityResolver`; only the small
`LuckPermsQueuePriorityResolver` adapter knows about LuckPerms, so ordering rules are unit-tested
without it.

## Redis coordination

Queue state reuses the shared Redis service and the existing event bus. Redis keys are namespaced as
`titansmp:<networkId>:server-transfer:v1:*`.

Every ownership-changing operation is an atomic Lua script (`join`, `leave`, `reserve`, `renew`,
`release`, expired-lease cleanup). A read-then-remove sequence is never used for allocation, so two
Velocity instances cannot allocate the same queued player.

Published events are the existing versioned, JSON-serialized, idempotently processable network events:

- `server.queue.updated` — position/size/priority for ActionBar and observability,
- `server.queue.left` — entry removed (`DISCONNECTED`, `LEFT`, `MOVED`, `TRANSFERRED`),
- `server.queue.transfer.started`,
- `server.queue.transfer.completed`,
- `server.queue.transfer.failed`.

## Reservations and race protection

When a backend has capacity, the coordinator:

1. determines the backend's server type,
2. inspects that type's queue,
3. selects the highest-priority eligible player (FIFO within the priority),
4. atomically creates a reservation with a `queue.reservation-timeout-ms` lease,
5. requests the connection through the existing transfer gateway,
6. removes the entry only after the connection succeeds.

If a reservation expires, the player becomes eligible again and the expiry is logged; the queue
entry is never permanently removed by a failed attempt. In-flight reservations are renewed while the
transfer is running so a slow transfer cannot be reallocated, and a hard lifetime bound forcibly
releases a reservation that never completes. Disconnect and manual server changes are idempotent and
clear both the queue entry and any reservation.

## ActionBar

Paper renders the queue ActionBar from `server.queue.updated` events and clears it on
`server.queue.left`. Updates are event-driven plus a configurable refresh interval
(`server-transfer.queue.actionbar-interval-ms`), never on every tick. Text is English only.

## Failure handling

- No eligible backend with capacity: the player is queued instead of failing.
- A backend becomes full between selection and connection: the connection is rejected and the
  player is queued.
- A queued transfer fails: the reservation is released, the entry is retained, and
  `server.queue.transfer.failed` is published.
- The player disconnects while queued: the entry and reservation are removed and
  `server.queue.left` is published.

## Data Bridge integration

The transfer service never implements its own prepare/ready/connect protocol. The Velocity gateway
calls Velocity's normal connection request; the existing `ServerPreConnectEvent` coordinator performs
the coordinated Data Bridge transfer (Prepare, TargetPrepared, Ready, revision checks, Connect,
Completed, Failed). The queue only decides *when* and *to which backend* the existing protocol runs.

## Future teleport-command integration

Future commands (`/tpa`, `/tpahere`, `/home`, `/rtp`) should depend on `ServerTransferService`:

```kotlin
service.requestJoin(playerUuid, serverType)
service.transferToServer(playerUuid, targetServerId)
service.queueStatus(playerUuid)
service.leaveQueue(playerUuid)
```

They do not need to know about Velocity connection APIs, Redis keys, queue internals, or the
Data Bridge protocol.
