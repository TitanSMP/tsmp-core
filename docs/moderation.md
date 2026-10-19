# Moderation

TitanSMP moderation is a network-wide service implemented in `modules:moderation`. Its public contracts and event DTOs live in the platform-independent `api`; the shared service and JDBC repository use the `core` DataSource and Redis EventBus. Paper and Velocity are adapters. Every player is identified by UUID; names are snapshots for display and audit history.

## Responsibilities and flows

- `ModerationService` is the single business boundary for ban, unban, mute, unmute, kick, active punishment lookup, and mute checks. It applies actor permission, self-target, and target-immunity checks before any write.
- The repository commits the active punishment change and its audit entry in one MariaDB transaction. Redis publishes the corresponding network event after the commit. A publish failure is logged and does not undo persisted state; MariaDB remains the source of truth.
- Paper exposes `/ban <player> [reason]`, `/unban <player>`, `/mute <player> [duration] [reason]`, `/unmute <player>`, and `/kick <player> [reason]`. Historical offline identities are resolved through the shared player service. Kick additionally requires network presence.
- Velocity checks the active persisted ban during `LoginEvent`, before backend connection, and denies login if the check fails closed or finds a ban. Paper checks again on join to cover a ban committed between proxy login and backend join. Both platforms consume ban/kick events to disconnect already-connected players. Paper refreshes mute state on join, consumes mute events, and checks MariaDB for asynchronous chat; a failed mute lookup blocks that chat message.

## LuckPerms permissions

The implementation queries LuckPerms using its normal permission API, including contextual query options where a platform user is available. Wildcards are interpreted by LuckPerms; TitanSMP does not implement wildcard matching or hard-code groups.

| Permission | Purpose |
| --- | --- |
| `titansmp.moderation.ban` | Ban a player |
| `titansmp.moderation.unban` | Remove an active ban |
| `titansmp.moderation.mute` | Mute a player |
| `titansmp.moderation.unmute` | Remove an active mute |
| `titansmp.moderation.kick` | Kick a connected player |
| `titansmp.moderation.immunity` | Prevent ban, mute, and kick |

Example assignments (illustrative only):

```text
Supporter: titansmp.moderation.mute
Moderator: titansmp.moderation.ban, .unban, .mute, .unmute, .kick
Admin:     titansmp.moderation.*, titansmp.moderation.immunity
Owner:     titansmp.moderation.*, titansmp.moderation.immunity
```

LuckPerms group configuration is external to this code. Console and system actors have no player UUID and bypass actor permission lookup; target immunity is still checked.

## Persistence and audit

Flyway migration `V4__create_moderation.sql` creates:

- `moderation_punishments`, with at most one current row per target UUID and punishment type (`BAN` or `MUTE`). A `NULL expires_at` is permanent. Queries only treat rows with no expiry or an expiry later than the current instant as active.
- `moderation_actions`, an append-only action history for `BAN`, `UNBAN`, `MUTE`, `UNMUTE`, and `KICK`. It records action and target UUIDs, actor and target name snapshots, reason, timestamps, expiry, and origin `server_id`. Console actors have a null UUID. Kicks never create active punishment rows.

Changing or removing an active punishment does not replace its earlier history entry. `UNBAN` and `UNMUTE` append their own entries. Expired rows cease to apply through query predicates; no expiry job is needed for correctness. Audit history has no player foreign key so identity cleanup cannot erase moderation records.

## Duration and commands

Durations accept positive integer values with `s`, `m`, `h`, `d`, or `w` suffixes, such as `30s`, `10m`, `2h`, `7d`, and `1w`. Zero, malformed duration-like input, and overflow are rejected with a command error. A mute with no duration is permanent. Since a free-form reason and an invalid duration occupy the same optional argument position, an ordinary word is treated as a permanent mute reason; `abc` and tokens containing digits are treated as attempted durations and rejected when invalid. Reasons default to `No reason provided` and are capped at 512 characters.

## Network consistency and future callers

The database write commits before publishing the Redis event. Redis is propagation, not the authorization/enforcement source of truth. Velocity always queries persisted ban state on login, and Paper performs a defensive ban check on join. If a ban races a server transfer, event propagation disconnects online sessions while the next login check reads the committed database state. Active mute checks likewise account for expiry in MariaDB.

Future Discord and Web API adapters should call the same `ModerationService`; they should not write moderation tables or publish events independently. The website must continue to use the Web API boundary rather than connect to MariaDB. The central mute query can later be reused by a voice-chat integration. No Discord bot, Web API implementation, standalone chat service, or voice-chat integration is included here.

## Tests

Service unit tests cover permission allow/deny, immunity, self-target protection, active bans and history, unban history retention, timed/permanent mute behavior, kick logging without active state, Redis publish failure, and duration parsing. Existing core event codec tests cover moderation-event serialization. The full repository build also compiles Paper and Velocity integrations and runs the existing database/Redis integration suites where Docker is available.
