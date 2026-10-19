# Discord Integration

The Discord integration links Minecraft accounts with Discord accounts and bridges the configured chat channel in both directions. It is one of the implemented feature modules; everything else that could be built on top of Discord (moderation, economy, friends, clans, auction, shop, statistics, a public Web API) is explicitly **not** part of this version.

## Discord IDs are configuration values

They are never hardcoded in source code. The chat channel, the guild, and the verified role are looked up from `config/titansmp.properties` at startup. A test scans the main sources of `modules/discord`, `services/discord-bot`, and `paper` and fails the build if an ID or an ID-shaped numeric literal appears there. Production values live only in the deployment environment or in `config/titansmp.properties`, which is gitignored.

## Library

The bot uses **JDA 6.7.0** (`net.dv8tion:JDA:6.7.0`), the established Java Discord API wrapper. The version is pinned in `services/discord-bot/build.gradle.kts` and documented here so it can be upgraded deliberately.

- `JDABuilder.createLight(token, GUILD_MESSAGES, MESSAGE_CONTENT)` — the bot uses no privileged gateway intent other than `MESSAGE_CONTENT`.
- `MESSAGE_CONTENT` is a **privileged intent**. It must be enabled for the bot application in the Discord developer portal, otherwise the Discord → Minecraft direction cannot read message text. The Minecraft → Discord direction keeps working without it because sending does not require content access.
- JDA's own `opus-java` dependency is excluded; it only serves voice audio and pulls native libraries this bot never loads.

## Runtime topology

The bot is a **dedicated runtime**, not a plugin that every Paper server starts. `services/discord-bot` is an `application` module whose main class is `de.titansmp.discord.DiscordBotMainKt`; exactly one instance of it owns the gateway, regardless of how many Paper servers run. No Paper server, and no Velocity proxy, ever opens a Discord connection.

Shared infrastructure (MariaDB pool, Flyway migrations, Redis event bus, server identity) comes from the same `TitanSmpRuntime` that Paper and Velocity use, so the bot reuses the existing connection and event systems instead of creating parallel ones.

`modules/discord` itself is **JDA-free**. It contains the linking service, the repository, the chat relay, the command handler, and the role listener as plain Kotlin against `api` and `core`, and is therefore testable without a gateway. `services/discord-bot` is the only module that touches JDA.

## Configuration

```properties
discord.enabled=false
discord.token=
discord.guild-id=
discord.minecraft-chat-channel-id=
discord.verified-role-id=
discord.linking.token-expiration-minutes=10
discord.linking.max-attempts-per-window=5
discord.linking.attempt-window-seconds=60
```

`discord.enabled=false` is the default and needs no credentials. When it is `false`, Paper does not register `/link` and `/unlink`, and the bot runtime exits immediately with an informational message instead of starting.

### Token handling

`discord.token` is empty in the example file and in Git. The real token is provided through the environment variable **`TITANSMP_DISCORD_TOKEN`**, which overrides whatever is in the properties file. The token is never written to logs, never included in an exception message, and `toString()` on the configuration object redacts it (`token=<redacted>`). The bot fails to start when a token is missing or invalid; there is no default production credential anywhere in the repository.

### Fail-fast startup

With `discord.enabled=true`, `start()` resolves the configured guild, chat channel, and verified role **before** any bridge is wired. If one of them does not resolve, a `DiscordStartupException` is thrown, the gateway is shut down, the process exits non-zero, and a clear message names the offending configuration key. The bot never starts "half a bridge" and never silently ignores a missing channel or role. If the configured guild is unavailable at runtime, the gateway listener drops messages from other guilds, and slash commands are only accepted from the configured guild.

## Database

The Flyway migration is `modules/discord/src/main/resources/db/migration/V5__create_discord_account_links.sql`. V5 follows the existing core migrations V1–V4 and is picked up automatically by the shared migration runner, which scans `classpath:db/migration` on every runtime.

`discord_account_links`
| column | type | constraint |
| --- | --- | --- |
| `player_uuid` | `BINARY(16)` | primary key |
| `player_name` | `VARCHAR(16)` | denormalized display aid, never a key |
| `discord_user_id` | `BIGINT` | unique |
| `linked_at` | `DATETIME(3)` | UTC |
| `updated_at` | `DATETIME(3)` | UTC |

`discord_link_tokens`
| column | type | constraint |
| --- | --- | --- |
| `token_hash` | `CHAR(64)` ascii_bin | primary key |
| `player_uuid` | `BINARY(16)` | foreign key to `players(uuid)` |
| `player_name` | `VARCHAR(16)` | denormalized display aid |
| `created_at` | `DATETIME(3)` | UTC |
| `expires_at` | `DATETIME(3)` | UTC |
| `used_at` | `DATETIME(3)` nullable | UTC; `NULL` means unused |

The link table's unique constraints are the last safety net for the 1 Minecraft UUID ↔ 1 Discord account invariant: a UUID can be linked only once, and a Discord account can be linked only once. The token table's `player_uuid` foreign key references `players(uuid)`; the Minecraft name is stored only as a display aid and is never used as a foreign key or as an identity. UUID is the stable identity in both directions.

## Link codes

`/link` in Minecraft creates a code that is:

- **cryptographically random** — 10 characters from the 32-character alphabet `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, drawn from `SecureRandom`. That is 50 bits of entropy per code; it is neither sequential, nor a UUID, nor a static short string, and the alphabet excludes `0`, `O`, `1`, `I` to avoid transcription mistakes.
- **time-limited** — it expires after `discord.linking.token-expiration-minutes` (default 10 minutes).
- **single-use** — a single `UPDATE discord_link_tokens SET used_at = ... WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?` makes redemption atomic. An expired or already-used token is rejected by the same statement.
- **stored hashed** — only a SHA-256 hash of the normalized code is persisted. The plaintext code exists only in the one chat message that is shown to the player, and it is never logged.

A player may have at most one outstanding unused token; requesting a new code replaces the previous one.

### Rate limiting

Redemption attempts are limited per Discord user with a fixed in-memory window, configured by `discord.linking.max-attempts-per-window` and `discord.linking.attempt-window-seconds`. The limiter runs **before** any database access, so a brute-force attempt never touches the token table. Nothing about a failed attempt is persisted; there is no row, counter, or permanent entry per attempt.

## Commands

### Minecraft: `/link` and `/unlink`

Both commands are registered by `paper` only when `discord.enabled=true`, and both work for every player — they require no special Discord role, because a link code's identity is the Minecraft account that mints it.

`/link` prints a fresh code exactly once, together with the expiry time. `/unlink` removes the caller's own link and can never touch another player's link: the lookup key is always the caller's UUID, and there is no target argument to get wrong.

### Discord: `/link <code>` and `/unlink`

Both are registered guild-scoped through `Guild.updateCommands()`, so they are available immediately without the global-command propagation delay. Both replies are **ephemeral**, so a link code and any error message stay private to the person who ran the command. Both are deferred with `deferReply(true)` first, because the database and role work run asynchronously.

`/link <code>` validates the code (exists, unused, not expired, not rate-limited), resolves the Minecraft UUID from the stored token, inserts the link, and only then assigns the verified role. An invalid code produces an understandable message rather than a generic failure. `/unlink` removes the caller's own link.

## Verified role

The role comes from configuration only; the bot never searches for a role by name. Role assignment is **best-effort and never blocks the database write**:

- If the role assignment succeeds, the reply confirms that the account is linked and verified.
- If the role assignment fails — Discord unreachable, role deleted, member left the guild, missing permissions — the database link is still kept, the player is told to contact an admin, and a warning is logged. The player is not told that everything succeeded.
- The role is removed on unlink on a best-effort basis. If Discord is unreachable at that moment, the database link is still removed and a warning is logged. There is **no blind retry loop** and no permanent database entry per failed role operation.

A deleted or renamed role is therefore an operational problem that shows up in the log and in the players' replies, not a silent state divergence.

## Chat bridge

Only `discord.minecraft-chat-channel-id` is processed. There is no global bridge and no other channel is read.

### Minecraft → Discord

`ChatService` → `NetworkChatMessage` on the Redis event bus → the bot's subscription → the configured channel. The bot has no direct Paper dependency and opens no second Redis connection; it subscribes to the same `EventBus` that Paper and Velocity use.

The relay drops every message whose source is not `MINECRAFT`, which makes it structurally impossible for the bot to re-send a Discord-originated message back into Discord. It also ignores messages authored by a bot.

Plain text only in v1; no embeds.

`[MC] [Admin] Henry: Hello` — the rank prefix comes from the shared `PlayerDisplayService`, resolved from `PlayerDisplayMetaChanged` events published by the Minecraft servers.

### Discord → Minecraft

`DiscordGatewayListener` → `DiscordChatRelay` → the shared `ChatService` → Redis → every Minecraft server, where it is distributed and rendered with `ChatMessageSource.DISCORD`. The bot never writes to Paper and never fakes a Minecraft UUID.

The Discord sender is resolved through `discord_account_links`. A Discord user who is **not linked** cannot forward a message to Minecraft; they get a hint to run `/link` in Minecraft first, and the message is not forwarded. A linked Minecraft player does not need to be online for the Discord side to work — `/link <code>`, `/unlink`, and the role all work while the player is offline. The reverse only works when a Minecraft server is connected to Redis to distribute the message; if nobody is online the message is dropped. There is no chat history and no replay.

### Display formatting

| direction | rendered as |
| --- | --- |
| Discord → Discord (via Minecraft player) | `[Discord] Henry: Hello` |
| Discord → Minecraft | `[Discord] [Admin] Henry: Hello` |
| Minecraft → Discord | `[MC] [Admin] Henry: Hello` |
| Minecraft → Minecraft | `[Admin] Henry: Hello` (unchanged) |

Discord-originated lines are rendered by the shared `PlayerDisplayFormat.chatMessage(source, prefix, name, message)` overload, so the `[Discord]` marker and the prefix handling are decided in one place and are the same on every platform. When a Discord sender has no known prefix — the common case, since Discord users are not necessarily LuckPerms-backed — the line falls back to the plain name. The Discord module performs no LuckPerms lookup of its own.

### Loop prevention

Three independent guards:

1. The outbound relay drops any `NetworkChatMessage` whose source is not `MINECRAFT`.
2. The inbound listener drops messages from bot authors.
3. The inbound listener drops messages from every guild and every channel except the configured pair.

The bot's own outbound message therefore comes back as a `MINECRAFT`-sourced message in Discord, is ignored at step 2, and never reaches Minecraft.

## Unlink propagation

`/unlink` in Minecraft and `/unlink` in Discord both go through the shared `DiscordLinkService`. The service publishes a `DiscordAccountUnlinked` network event (`discord.account_unlinked`) on the Redis bus after a successful removal. The bot subscribes to it and removes the verified role best-effort, ignoring the echo of its own `sourceServerId`. If Discord is unreachable, the log says so and the database stays consistent — the link is gone either way.

## Error strategy

| failure | behaviour |
| --- | --- |
| `discord.enabled=true`, token missing/invalid | clear log, clean lifecycle stop, non-zero exit |
| configured guild not resolvable | `DiscordStartupException`, clean stop |
| configured chat channel not resolvable | `DiscordStartupException`, clean stop |
| configured verified role not resolvable at startup | `DiscordStartupException`, clean stop |
| verified role assignment fails after a successful link | link is kept, warning reply, warning log |
| verified role removal fails after a successful unlink | unlink is kept, warning log |
| database failure | `DATABASE_FAILURE` translated to an understandable reply, root cause logged |
| rate limited | understood before any database access, honest message |

Every translation from an internal exception to a player-facing message is explicit; a generic "could not be processed right now" is the floor, never a silent success.

## Security

- Link codes are never logged, and neither is the bot token. Exception messages never carry either value.
- Codes are stored only as SHA-256 hashes of the normalized code.
- Codes are single-use, short-lived, and redeemed through an atomic `UPDATE`.
- Link attempts are rate-limited per Discord user, and nothing about a failed attempt is persisted.
- Discord IDs and Minecraft UUIDs are minimized in logs and are never printed in public Discord messages.
- Both `/link` and `/unlink` replies on Discord are ephemeral.

## Out of scope in v1

Discord moderation, economy, friends, clans, auction house, admin shop, statistics, and a public Web API. The Discord command surface is exactly `/link` and `/unlink`, and a test enforces it.

## Running the bot

```shell
./gradlew :services:discord-bot:run
```

The process reads `config/titansmp.properties` and the `TITANSMP_DISCORD_TOKEN` environment variable, connects the gateway, applies the existing database migrations, and only then registers the bridges. Stopping it shuts the gateway down and closes the shared runtime cleanly.
