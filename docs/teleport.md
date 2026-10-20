# Network Teleport

`modules:teleport` stores player and clan home locations in MariaDB V10, keeps short-lived requests in MariaDB, and reserves cooldowns atomically in the shared Redis service. It reuses the existing `EventBus` and Velocity server-transfer coordinator; it adds no connection pool or transfer coordinator.

## Commands

- `/tpa <player>` asks the player to accept a teleport to their current location.
- `/tpahere <player>` asks the player to accept a teleport to the requester's saved location.
- `/tpaccept` accepts the oldest pending request; `/tpdeny` rejects it. Equal timestamps are ordered by request UUID. Requests expire after the configured number of seconds.
- `/sethome <name>`, `/home <name>`, and `/delhome <name>` manage personal homes.
- `/rtp` searches the current server's loaded chunks for a safe location, bounded by the configured attempt count.
- `/clan sethome <name>`, `/clan home [name]`, and `/clan delhome <name>` are handled inside the existing clan command. Admins manage homes; all clan members can use them. Omitting a name only works when exactly one home exists.

TPA requests use UUIDs, reject self requests, expire from the database, and prevent duplicate pending requester/target pairs. The target's location and server are resolved on the backend where `/tpaccept` is run for `/tpa`. For `/tpahere`, accepting sends a one-shot request to the requester's current backend to capture their current location; the accepted player then transfers there. Offline requesters cannot be accepted.

## Cooldowns and LuckPerms

The default cooldown is 30 minutes for each of `TPA`, `TPAHERE`, `HOME`, `RTP`, and `CLAN_HOME`. Redis `SET NX` with expiry is the network-wide atomic reservation, so backend changes do not reset a cooldown. Redis restart clears it. Home cooldowns include the lowercase normalized home name in their key; the five categories have independent keys.

LuckPerms numeric nodes determine permissions without rank-name checks:

- `titansmp.teleport.cooldown.<minutes>`: highest valid numeric value wins; default is 30.
- `titansmp.teleport.homes.<number>`: highest valid value wins; default is 1; `.0` means unlimited and overrides finite values.
- `titansmp.teleport.clan.homes.<number>`: only current clan Admins contribute; the clan limit is the maximum Admin value, never a sum. `.0` from any Admin means unlimited. Default is 1.

Clan home capacity is resolved from current Admin LuckPerms inherited nodes when an Admin creates a home; the result is not cached. The repository locks the clan row while checking and inserting homes, so concurrent creations share one capacity.

## Persistence and cross-server behavior

V10 creates `player_homes`, `clan_homes`, and `teleport_requests`. Home ownership uses player or clan UUIDs. Locations include server ID, world, coordinates, yaw, pitch, and timestamps. Names are stored in lowercase normalized form with their original display form. Creation is transactional; player and clan row locks serialize capacity checks. Homes are not deleted if their world is later unavailable.

Cross-backend home and accepted TPA movement writes a short-lived destination to the shared Redis service and publishes a versioned `teleport.transfer_requested` event. Velocity consumes that request with the existing `ServerTransferService.transferToServer`, which delegates to the Data Bridge transfer lifecycle. The destination Paper backend applies the pending operation only after the player arrives, checks that the named world exists, and uses the operation ID to prevent duplicate application. Failed transfer requests do not teleport the player; pending data expires automatically. No server names are hardcoded.

RTP only uses server types listed in `teleport.rtp.server-types`; an empty list disables it. On an eligible backend it searches locally. From another backend it chooses the first configured type in sorted order and calls the existing queue-aware Server Transfer service; after arrival, Paper searches that backend's already-loaded chunks and checks the world border, solid ground, headroom, lava, and world height. If no safe location is found, the cooldown is released. Full queues use the existing server-type queue behavior.

## Configuration

```properties
teleport.enabled=true
teleport.default-cooldown-minutes=30
teleport.default-home-limit=1
teleport.tpa.request-expiration-seconds=60
teleport.rtp.max-attempts=20
teleport.rtp.server-types=
teleport.clan.default-home-limit=1
```

Set `teleport.rtp.server-types` to comma-separated server types configured for this network. The system does not infer world mappings between servers; each home stores its authoritative server and world.
