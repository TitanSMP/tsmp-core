# Network Chat

TitanSMP chat distributes Minecraft messages between Paper servers through the existing Redis EventBus. The `modules:chat` implementation has no Paper dependency; public messages, source values, service contract, and event DTO are in `api`. Paper is an adapter that cancels vanilla distribution, delegates send checks to the shared service, and renders accepted Redis events. No chat history is stored.

## Message and service model

`ChatMessage` contains a UUID `messageId`, nullable `senderUuid`, display-only `senderName`, plain text `message`, origin `serverId`, UTC `Instant timestamp`, and a `ChatMessageSource` (`MINECRAFT`, `DISCORD`, `WEB`, or `SYSTEM`). Minecraft messages require a sender UUID. External and system messages can omit it without inventing a fake player identity.

`ChatService.send` validates a Minecraft message, checks `titansmp.chat.use`, asks the existing `ModerationService.isMuted` for the current MariaDB-backed mute state, applies the per-UUID limiter, and publishes `NetworkChatMessage`. `broadcast` publishes a validated message from another source for future adapters. Redis publish failure is returned to the caller; Paper shows an error and does not render locally. The message is rendered on the origin server only when its Redis event arrives, so there is no local fallback that could double-display it.

The existing EventBus uses the shared `titansmp:<network-id>:events:v1` channel and versioned JSON envelope. Each subscriber ignores already-seen `messageId` values with a bounded cache (4,096 entries, five-minute TTL). Pub/Sub is transient and does not provide persistent history or guaranteed replay/order.

## Paper and moderation

Paper uses the current `AsyncChatEvent` from the Paper event API. It extracts the plain text component, cancels vanilla distribution, and invokes `ChatService` asynchronously. Incoming events are scheduled onto the Paper main thread and sent to local online players as plain text. The default format is `[SMP-01] Henry: Hello`; the `ChatRenderer` abstraction can change this without moving formatting rules into the business service. System messages use `[System] ...`.

Muted players are denied by the shared moderation service, not by a second mute table or local-only cache. Permission and moderation lookup failures fail closed and show a readable error. The message never publishes if the player lacks chat permission, is muted, exceeds the limit, sends only whitespace, or cannot publish to Redis.

## Permissions

LuckPerms remains the permission authority. The chat service reuses the existing platform permission adapter and performs normal LuckPerms node queries; it does not store ranks, prefixes, groups, or permission data.

| Permission | Current behavior |
| --- | --- |
| `titansmp.chat.use` | Required to send network chat |
| `titansmp.chat.color` | Reserved for future color handling; color codes are not enabled |
| `titansmp.chat.format` | Reserved for future formatting controls |
| `titansmp.chat.global` | Reserved for future chat modes |

## Configuration

The existing `config/titansmp.properties` supports these optional settings:

```properties
chat.rate-limit.enabled=true
chat.rate-limit.messages-per-window=5
chat.rate-limit.window-ms=3000
chat.max-message-length=256
```

Equivalent environment variables are `TITANSMP_CHAT_RATE_LIMIT_ENABLED`, `TITANSMP_CHAT_MESSAGES_PER_WINDOW`, `TITANSMP_CHAT_WINDOW_MS`, and `TITANSMP_CHAT_MAX_MESSAGE_LENGTH`. Defaults apply when omitted. Values are validated during configuration loading. The in-memory fixed-window rate limiter keys by player UUID, is shared by all players handled on one server process, and resets naturally after the window; it is not persisted or coordinated across backends.

Messages are rejected if empty or whitespace-only. Length is measured in Kotlin/Java UTF-16 characters against the configured maximum and overlong messages are rejected with an explanation rather than truncated. No formatting/color replacement is performed.

## Identity and future sources

The origin server ID comes from the shared `ServerIdentity`; chat does not create server identities or player records. Server transfer requires no chat-state migration. A later Discord adapter or Web API can call the platform-independent `ChatService`/event contract and use `DISCORD` or `WEB`; no separate Redis transport is needed. System events can use `SYSTEM`. The current implementation has no Discord bot, Web API, persistent chat history, rank/prefix integration, or voice-chat behavior.

## Tests

Unit tests cover message validation, empty and overlong content, mute and permission denial, the per-UUID rate limit and window reset, Redis failure, system messages without player UUID, and duplicate ID cache expiry/capacity. Core serializer tests round-trip every source value and nullable sender UUID. Configuration tests exercise the defaults, overrides, and invalid values.
