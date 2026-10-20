# Clans

`modules:clans` provides the network-wide clan service over the shared MariaDB pool and Redis EventBus. UUIDs identify clans and players; display names are resolved through the existing Player Identity service. Clan role state is stored in MariaDB and never mapped to LuckPerms groups.

## Roles and lifecycle

Each clan has a unique UUID, case-insensitive unique name and tag, creation time, color configuration, and members. Roles are `Member < Moderator < Admin`. The creator becomes Admin. Moderators can invite and kick; Admins can promote, demote, disband, and configure clan colors. At least one Admin is preserved: the last Admin cannot leave, be kicked, or be demoted. When a clan becomes empty its row and dependent invitations are deleted transactionally.

Each member UUID is the primary key in `clan_members`, so a player can belong to at most one clan. V9 adds clan/player lock rows. Mutations lock the clan row and then sorted player lock rows in a `READ COMMITTED` transaction. Unique normalized-name/tag keys settle concurrent creation, and the membership primary key plus player lock settles accepts from multiple invitations. Membership changes, final-Admin checks, invitation changes, and disband cascades commit atomically.

## Commands

- `/clan` shows role-appropriate help.
- `/clan create <name> <tag>` creates a clan (defaults: name 3–24, tag 2–8 alphanumeric characters).
- `/clan invite <player>`, `/clan accept <clanname>`, `/clan reject <clanname>`, `/clan invitations` manage persisted invitations.
- `/clan promote <player>`, `/clan demote <player>`, `/clan kick <player>`, `/clan leave`, `/clan disband` manage membership.
- `/clan info` shows role, network online state, member count, and color configuration.
- `/clan color <color>` configures a standard Minecraft color. `/clan color rainbow <color>` stores rainbow mode with a base color.
- `/clan chat <message>` and `/c <message>` send clan-only chat.

Clan name/tag limits, page size, invitation expiration, and feature enablement are configured with `clans.*`. Invitations default to 24 hours and are cleaned opportunistically when listed or replaced. Accept verifies expiry again in the transaction.

## Invitations, blocks, and network behavior

Invitations are UUID-keyed rows and support offline targets. Clickable accept/reject commands pass the clan UUID; the command's player UUID provides the recipient identity, and MariaDB verifies the exact invitation, membership eligibility, expiry, and clan existence. Where the Friends service is enabled, invitations are blocked in either direction when the players have blocked one another.

The API has no Paper, Bukkit, Velocity, LuckPerms, SQL, Redis, Adventure, or JDA dependencies. Name resolution uses the shared player identity database. Local Paper servers receive Redis events and only deliver chat or notices to their own online players, so clan chat crosses backend servers without entering global chat or creating a second transport.

## Colors, display, and chat

LuckPerms checks only `titansmp.clan.color` and `titansmp.clan.color.rainbow`; the rainbow node is additionally required for rainbow mode. Clan roles remain entirely in MariaDB. Standard and rainbow base colors are stored on the clan. Clan chat renders `[Clan] [TAG] Player: message`, with the configured color; rainbow mode cycles a palette offset by the stored base color.

The clan-backed `DisplaySuffixProvider` contributes `[TAG]` to `PlayerDisplayMeta.suffix`. The existing Display service publishes metadata changes for creation, joining, leaving, kicking, and disbanding. Velocity renders the suffix in TAB only. LuckPerms remains the prefix source, and Paper nametags and chat do not receive the suffix.

## Events

Version 1 clan events reuse `NetworkEventSerializer` and the existing EventBus: `clan.created`, `clan.disbanded`, `clan.member_joined`, `clan.member_left`, `clan.member_kicked`, `clan.member_role_changed`, `clan.invitation_created`, `clan.invitation_accepted`, `clan.invitation_rejected`, and `clan.color_changed`. Clan chat uses `clan.chat_message`. Events carry UUIDs, roles, minimal display fields, and affected member UUIDs for disband cleanup. MariaDB remains authoritative; events are post-commit network notifications.
