# Friends

Friends are network-wide and use UUID identity records maintained by core. `api` contains platform-independent service contracts, DTOs, paged results, result states, and versioned event contracts. `modules:friends` owns the MariaDB repository and asynchronous domain service. Paper registers `/friends` with `/friend` as an alias and resolves names through the shared `PlayerService`.

## Database and concurrency

Flyway V8 creates canonical UUID-ordered friendship pairs, directed pending requests, directed blocks, and pair-lock rows. Uniqueness constraints prevent duplicate relationships and requests. Each two-player transition locks the same canonical pair row in a MariaDB transaction, serializing request, accept, reject, removal, block, and unblock across backend servers. Accept verifies the request and both block directions under that lock, creates the friendship, and removes pending requests in both directions atomically. Blocking removes the friendship and pending requests before storing the block. Unblocking only deletes that directed block.

## Commands and pagination

- `/friends` or `/friend`: overview
- `/friends add <player>`
- `/friends accept <player>` and `/friends reject <player>`
- `/friends remove <player>`
- `/friends block <player>` and `/friends unblock <player>`
- `/friends requests [page]`
- `/friends list [page]`

`friends.page-size` configures the SQL `LIMIT`/`OFFSET` page size (default 10; valid range 1–100). Lists use a separate count query. Names and network online state come from shared player identity, not backend scans. Offline targets can be resolved from persisted identities.

## Requests and notifications

Request creation commits before publishing `friend.request_created`. The target receives clickable accept/reject actions when online. The action includes only the requester UUID; the executing player's UUID identifies the recipient, and the database request is revalidated transactionally. Offline requests remain in MariaDB and are shown on a true `PlayerConnected` event. Backend moves use `PlayerServerChanged` and do not repeat join notifications. Accept, reject, and remove events are delivered through the existing Redis EventBus to the backend hosting each online player. No chat notification is queued for offline friends.

Friendship and block state in MariaDB is authoritative; Redis events are transient delivery signals. Events are `friend.request_created`, `friend.request_accepted`, `friend.request_rejected`, `friend.removed`, `friend.blocked`, and `friend.unblocked`, serialized by the existing `NetworkEventSerializer`. Friend join notifications reuse `player.connected`.

Blocks are directional, prevent requests in either direction, remove friendships and pending requests, and suppress the blocked player's join notice to the blocker. Unblocking does not restore removed state.
