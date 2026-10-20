# Economy

TitanSMP has one network-wide currency, **TitanCoins**, implemented in `modules:economy`. Its public contracts live in the platform-independent `api`; the shared service and transactional JDBC repository use the `core` DataSource, Flyway, the shared player service, and the Redis EventBus. Paper is the only command adapter. MariaDB is the authoritative balance store and Redis only propagates committed changes.

The identity is always the player UUID. Usernames are mutable snapshots used for display and lookup; no balance or audit row is keyed by a name.

## Money representation

`Money` is a `@JvmInline value class` over a `Long` count of minor units. One TitanCoin is 100 minor units, so `100` means `1.00 TitanCoins`. Floating-point types are never used for balances, transfers, or persistence.

- `Money.ofMinor(Long)` wraps an already exact amount and permits signed values for internal arithmetic.
- `Money.of(String)` parses command input such as `10`, `10.5`, or `10.50`. It rejects empty input, negative values, malformed input, grouping separators, and more than two decimal places. No implicit rounding occurs.
- `plus` and `minus` use `Math.addExact`/`Math.subtractExact` and raise `MoneyOverflowException` instead of silently wrapping.
- `format()` is locale-independent: `,` groups thousands, `.` separates decimals, and exactly two fraction digits are always rendered (`1,250.50`). `TitanCurrency.label` appends the display name.

`TitanCurrency` fixes the currency id `TITANCOINS`, display name `TitanCoins`, and scale. There is intentionally no multi-currency abstraction.

## Commands and permissions

| Command | Behaviour |
| --- | --- |
| `/balance` | Shows the caller's balance. Console must use the argument form. |
| `/balance <player>` | Shows another player's balance; offline names resolve through the shared player service. |
| `/pay <player> <amount>` | Transfers TitanCoins to a **network-online** player, including a player on another backend. |
| `/balancetop` | Shows the richest balances (default 10, at most 50). |
| `/eco <give\|take\|set> <player> <amount>` | Administrative balance mutation. `set` accepts zero; `give` and `take` require a positive amount. |

Aliases are declared in `paper/src/main/resources/plugin.yml`: `bal`/`money`, `send`, `baltop`, and `economy`.

| Permission | Purpose |
| --- | --- |
| `titansmp.economy.balance` | Check the own balance |
| `titansmp.economy.balance.others` | Check another player's balance |
| `titansmp.economy.pay` | Send TitanCoins |
| `titansmp.economy.balancetop` | View the leaderboard |
| `titansmp.economy.admin` | Give, take, and set balances |

No node is granted implicitly. All checks are delegated to LuckPerms through `LuckPermsEconomyPermissionService`; TitanSMP does not interpret wildcards, groups, or rank names, and an unset node is denied. Example assignments (illustrative only):

```text
Player:  titansmp.economy.balance, titansmp.economy.pay, titansmp.economy.balancetop
Staff:   titansmp.economy.balance.others, titansmp.economy.admin
```

A null requester/actor represents a trusted console or system caller and bypasses the *actor* permission check, matching the moderation actor convention. Target-side rules such as "receiver must be online" still apply.

`/pay` rejects self-transfers, zero, negative, malformed, and overflowing amounts before touching the database. Offline payment is deliberately not supported: the receiver must be present in the shared network presence data, not merely known in the player table. Command output and errors are English, consistent with the existing Paper commands.

## Persistence

Flyway migration `modules/economy/src/main/resources/db/migration/V6__create_economy.sql` adds two tables:

- `economy_balances` holds one row per wallet: `player_uuid`, `balance` in minor units, `created_at`, and `updated_at`. The primary key is the UUID. A non-negative `CHECK` protects the invariant, and the descending `(balance, player_uuid)` index matches the leaderboard query. The foreign key to `players` uses `ON DELETE CASCADE`, so identity cleanup cannot leave an orphan wallet.
- `economy_transactions` is the append-only audit trail. Each row records `transaction_id`, the affected `player_uuid`, an optional `counterparty_uuid`, the reason `type`, the requested/resulting amount, before/after balances, `source_server_id`, an optional admin `actor_uuid`, and `created_at`. The primary key is `(transaction_id, player_uuid)`.

The audit table intentionally has **no foreign key** so history survives identity cleanup. It also has no `CHECK` on `type`; new reasons can be recorded without a migration. All amounts and balances in the audit table are stored as minor units and constrained to be non-negative. `EconomyReason` currently contains `PAYMENT`, `ADMIN_GIVE`, `ADMIN_TAKE`, and `ADMIN_SET`.

A missing balance row reads as `Money.ZERO` without creating a row. The first mutation inserts the row with `INSERT ... ON DUPLICATE KEY UPDATE`, then locks it.

## Atomicity and concurrency

Every mutation runs in one JDBC transaction on a connection from the shared pool:

1. Create the balance row if needed.
2. Select the affected row(s) with `SELECT ... FOR UPDATE`.
3. Validate the operation and compute the new balance(s) with checked arithmetic.
4. Update the balance row(s) and insert the audit row(s).
5. Commit; roll back the whole transaction on any failure.

A transfer locks both balances in ascending UUID order. That deterministic order prevents deadlocks between concurrent transfers in opposite directions and ensures both accounts observe the same committed movement. A `/pay` writes exactly two audit rows sharing one `transaction_id`; each row names the other side as `counterparty_uuid`. Insufficient funds, negative results, overflow, and missing rows abort the transaction, so no partial transfer or orphan audit entry is possible.

Balance changes are never computed from a previously read API value. Validation and mutation happen under the same row lock, which prevents lost updates. The service generates the transaction id and timestamp before the write so the committed row and the later event agree.

## Network propagation

After the database commit, the service publishes `EconomyBalanceChanged` (`economy.balance_changed`) once per affected account. A transfer therefore produces two events with the same `transactionId`. The event carries player UUID, amount, old/new balance, reason, counterparty UUID and name, actor UUID, and source server id.

Publishing is best-effort propagation only: a Redis failure is logged at severe level and does not roll back or rewrite the committed balance. Consumers must treat events as hints, tolerate duplicates and reordering, and re-read MariaDB when correctness matters. Paper uses the event to notify a locally connected receiver about an incoming payment; it does not cache balances or use Redis as state.

## Paper integration

`EconomyPaperModule` registers the four commands with the existing TitanSMP Paper plugin and subscribes to `EconomyBalanceChanged`. One plugin JAR remains; `modules/economy` is bundled into it. The module is created during runtime startup with:

- `JdbcEconomyRepository` over the shared DataSource,
- `DefaultEconomyService` with the shared player service, EventBus, and a dedicated economy executor,
- `LuckPermsEconomyPermissionService` over the plugin's LuckPerms instance.

Resolving a target checks locally connected players first, then the shared player service on a worker thread. JDBC and permission work never runs on the server thread; Bukkit messages and scheduler tasks are marshalled back to it. Shutdown closes the event subscription, stops the economy executor, and then closes the shared runtime.

## Extension points

`EconomyService` also exposes permission-free `deposit`, `withdraw`, `setBalance`, and `transfer` methods with an explicit `EconomyReason`. Future auction houses, shops, quests, or rewards should call these methods inside the same service/repository path and add a reason constant; they must not write the economy tables or publish economy events independently.

Not implemented here: auction house, player shops, jobs, banks, interest, taxes, multi-currency, offline payments, pay confirmation flows, and Discord/Web API economy commands. The website must continue to use the Web API boundary rather than connect to MariaDB.

## Tests

- `api` unit tests cover parsing, exact minor units, formatting, negative/malformed input, and overflow.
- `modules:economy` service tests cover permissions, self-pay, zero/negative/overflow amounts, network presence, transfers, admin operations, audit correlation, event publication, and publish-failure handling against an in-memory repository.
- `JdbcEconomyRepositoryIntegrationTest` uses Testcontainers MariaDB (skipped when Docker is unavailable) to verify real Flyway V6 migration, atomic transfers, concurrent mutation without lost updates, audit rows, and leaderboard ordering.
- `core` serializer tests cover the `economy.balance_changed` wire format round trip.
- `paper` MockBukkit tests cover command usage, permission/service delegation, leaderboard rendering, and incoming-payment notification.
