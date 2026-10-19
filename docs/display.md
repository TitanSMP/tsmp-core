# Player Display and Network TAB

TitanSMP resolves player rank display data once, in a shared layer, and reuses it for the network TAB list,
Paper nametags, and chat. LuckPerms remains the single source of truth for prefix, metadata, and rank weight.
The display layer never reads a rank from a second database, never hardcodes weight, and never calls
PlaceholderAPI or uses `%luckperms_prefix%`. Public messages, contracts, and the Redis event live in `api`;
the LuckPerms adapter and the platform-independent service live in `modules:display`.

## Public contracts (`api`)

- `PlayerDisplayMeta` holds `playerUuid`, `prefix`, `suffix`, and `weight`. `prefix` is the already-resolved
  LuckPerms prefix (possibly empty). `suffix` is reserved for the future clan tag and stays `null`/empty until
  a clan system provides it. `weight` is the LuckPerms rank weight used for ordering and is never hardcoded.
- `DisplayProvider` resolves `PlayerDisplayMeta` for a UUID.
- `DisplaySuffixProvider` optionally supplies a clan suffix. No implementation exists yet; the future
  `modules:clans` module will register one. The suffix flows into `PlayerDisplayMeta.suffix` and is rendered
  only by the TAB list.
- `PlayerDisplayService` is the central read model used by TAB, chat, and nametags. It caches results per
  UUID, exposes the cache, supports explicit refresh, and invalidates entries.
- `PlayerDisplayMetaChanged` (`display.player_meta_changed`) is the Redis event published when assembled
  display meta actually changes.

`api` has no LuckPerms, Paper, Velocity, Redis, MariaDB, or PlaceholderAPI dependency. A bytecode test guards
this boundary.

## Implementation (`modules:display`)

- `LuckPermsDisplayProvider` (with `LuckPermsRankSource`) is the only place that reads rank data for display.
  It reads LuckPerms' own user cache (`User.cachedData.getMetaData(options).prefix`) and loads the user only
  on a cache miss. The weight is the primary group's configured `Group.weight`; an absent group or weight is
  `0`. Context query options are used, matching the moderation permission adapter.
- `DefaultPlayerDisplayService` combines the provider with any registered suffix providers, caches the result
  per UUID, and publishes `PlayerDisplayMetaChanged` on real changes. It ignores the echo of its own events so
  a server does not loop on its own change. The cache is filled from LuckPerms cached data, so rendering never
  triggers a fresh permission lookup by itself.
- `LuckPermsDisplayListener` subscribes to LuckPerms recalculation events: a user recalculation refreshes that
  player, a group recalculation refreshes every cached player. Refresh re-reads LuckPerms and publishes only
  when the assembled meta differs.
- `PlayerDisplayFormat` is the single formatter. TAB, chat, and nametags route through it so the prefix is
  rendered identically everywhere instead of each component inventing its own formatting.
- `TabListRow` and `TAB_LIST_ORDER` model one TAB row and order rows by weight (higher first) then by
  case-insensitive name.

## Rendering rules

| Surface | Rule | Clan suffix |
| --- | --- | --- |
| TAB (Velocity) | `prefix + name + clan suffix` | yes |
| Chat | `prefix + name: message` | never |
| Nametag (Paper) | `prefix + name` | never |

No surface resolves a prefix on its own. The prefix always comes from `PlayerDisplayService`; the suffix is
only ever rendered by the TAB list.

## Network TAB (Velocity)

Velocity owns the network-wide TAB list because it sees every player regardless of backend. `VelocityTabListModule`
registers `PostLoginEvent`, `DisconnectEvent`, and `ServerConnectedEvent`, and reacts to `PlayerDisplayMetaChanged`.
For each viewer it sets the display name and list order of every online player's entry, so the list is consistent
network-wide. Higher LuckPerms weight ranks first; the mapping uses the current list-order semantics where a higher
order value appears first (`rows.size - index`). Offline players are removed from every viewer's list.

No new TAB permission exists, and being listed requires no permission node. A one-second delayed refresh runs after
login in case rank data was still loading.

## Nametags (Paper)

`PlayerDisplayPaperModule` uses the stable Bukkit scoreboard-team API available on Paper: one team per player UUID
(`tsmp` + 12 hex characters, 16 characters total), the player's name as the team entry, and the team prefix set to
`PlayerDisplayFormat.nameTagPrefix(prefix)`. Teams are created on join, updated when meta changes, and removed on
quit and module close. All scoreboard work happens on the main thread. No packets, NMS, TAB plugin, or
PlaceholderAPI are used, and the clan suffix is never shown in a nametag.

## Chat integration

`DefaultChatService.send` resolves the sender's prefix through `PlayerDisplayService` and stores it on
`ChatMessage.prefix`, which travels over Redis with the message. Prefix resolution is best effort: if it fails,
the message is still sent with an empty prefix. The Paper chat renderer builds its text with
`PlayerDisplayFormat.chatMessage(prefix, name, message)`, so chat never resolves ranks itself and never renders a
clan suffix.

## Redis events

`PlayerDisplayMetaChanged` carries the new `PlayerDisplayMeta` so TAB, nametags, and future consumers (Discord,
Web API) react immediately. It uses the existing shared event bus and versioned JSON envelope; it is a change
notification, not a durable prefix store. The `EventMetadata.sourceServerId` lets the publisher ignore its own
echo. The core serializer round-trips the event, including a nullable suffix and the weight.

## Permissions

LuckPerms remains the permission and rank authority. The display layer performs normal LuckPerms queries and
stores no rank, group, or permission data of its own. No new permission node is introduced.

## Limitations and future work

- Prefixes are rendered as plain text through Adventure components; legacy or MiniMessage color codes in a
  LuckPerms prefix are not parsed in this iteration.
- The clan suffix is not implemented. It is prepared through `DisplaySuffixProvider` and rendered only in TAB;
  `modules:clans` will provide the value later.
- TAB is owned by Velocity; Paper does not render a per-backend TAB list.

## Tests

Unit tests cover the LuckPerms provider (prefix/weight, missing prefix, load-on-miss), the display service
(caching, change events on real changes only, remote updates, own-echo suppression, optional and blank clan
suffix), the formatter (all three rules, with and without suffix), and TAB ordering (weight first, then
case-insensitive name). Paper tests use MockBukkit to verify that a join applies the prefix to the nametag team,
that the clan suffix is never used, that a rank change updates the existing team, and that quit removes the team.
Core serializer tests round-trip the display event and the chat prefix. An `api` bytecode test asserts the
contract module references no platform or infrastructure types.
