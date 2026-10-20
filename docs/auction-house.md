# Network-wide Auction House

The Auction House stores marketplace listings in MariaDB and uses the existing Redis EventBus only for compact state-change notifications. Every Paper backend reads listings from the shared database; there is no authoritative listing cache.

## Commands and GUI

- `/ah` opens a 54-slot chest GUI. The first 45 slots show listings; the bottom row is ordered Expired Items, Purchased Items, Search, Previous Page, Auction House Info, Next Page, Placeholder, Sort, and Your Auctions.
- `/ah sell <price>` lists the item in the main hand. Prices use the exact `Money` parser, for example `250.00`; commas, exponent notation, NaN, infinity, and more than two fractional digits are rejected.
- `/ah expired` opens expired and cancelled item storage.
- `/ah purchased` opens purchased-item storage.
- `/ah cancel <auction-id>` cancels only the caller's active listing. The item remains reclaimable in Expired Items.
- `/ah info` prints the help text. `/auctionhouse` and `/auction` are the default aliases; `auction-house.aliases` changes the runtime alias list where supported by the server command map.

Clicking a listing opens a **Confirm Purchase** screen. Confirm revalidates and charges the auction; Cancel returns to browsing. Clicks, number-key swaps, shift-clicks, double-clicks, and drags are cancelled in Auction House inventories. Actions are mapped by internal screen holder and slot rather than inferred from display names.

Search closes the GUI and prompts for a keyword. The next message is intercepted before `ChatService` and is not broadcast. `cancel` aborts. Input state is per player, expires after the configured timeout, and is cleared on disconnect. Search is case-insensitive and matches material, display name, and lore through a parameterized query. The active term appears in the info item.

Sort cycles through Newest First, Oldest First, Highest Price, Lowest Price, and Your Auctions. Your Auctions is an active-seller filter in the main view; the dedicated button opens the player's active listings, where a click presents a cancellation confirmation. Main listings use configurable pagination and show seller, TitanCoins price, and time remaining.

## Items, purchase, and expiration

Paper serializes the complete item with `ItemStack.serializeAsBytes()` and restores it with Bukkit's item codec. This preserves metadata and PDC; the API carries opaque bytes plus a search-text index.

Listing removes the exact held stack before inserting an active row. A failed listing restores the stack and refunds any listing fee; if it cannot fit back into the inventory or the seller disconnects, it is saved under Expired Items. The command is serialized per player while pending. The configurable fee uses integer arithmetic, is rounded down to minor units, and is charged upfront through `EconomyService.withdraw`; its default is zero. An accepted listing fee is not refunded by later cancellation or expiration.

Purchase locks the listing in MariaDB, rejects self-purchases and expired/non-active rows, and records `PURCHASING` with the buyer and stable auction transaction ID. The shared EconomyService transfers TitanCoins from buyer to seller in its own atomic balance transaction with reason `AUCTION_HOUSE`. That idempotent transfer ID makes retries/recovery safe if a backend stops between the money commit and listing commit. The listing is then marked SOLD and a persistent Purchased Items row is inserted in the same auction database transaction. Startup recovery replays unfinished purchases with the same economy transaction ID. Sellers may be offline; buyers must be online to confirm.

Items are delivered through persistent Purchased Items rather than dropped. Expired and cancelled items remain on their listing until claimed. Claiming reserves a stable token, delivers the item with that token in its PDC, then acknowledges the claim in MariaDB. If the process stops after inventory insertion, a later login can reconcile the claim without granting the item again. A full inventory leaves the item claimable.

Expiration runs every minute and is also checked under the listing lock during purchase/cancellation. At the expiry boundary, the database row lock chooses one state transition. Expired listings remain stored until the seller claims them.

## Limits, permissions, and configuration

LuckPerms grants `titansmp.auction.sell` for listing. Active limits use inherited grants named `titansmp.auction.limit.<number>`; the highest valid numeric grant wins. `.0` means unlimited. If no numeric grant exists, `auction-house.default-auction-limit` applies. No rank or group names are hardcoded.

```properties
auction-house.enabled=true
auction-house.expiration-hours=48
auction-house.items-per-page=45
auction-house.search-timeout-seconds=30
auction-house.default-auction-limit=5
auction-house.sell-fee-percent=0
auction-house.aliases=auctionhouse,auction
```

Values are validated at startup: page size is 1–45, expiration is 1–720 hours, search timeout is 1–300 seconds, limit is non-negative, and fee is 0–100 percent.

## Database, economy, and network events

Flyway V11 creates `auction_house_listings` and `auction_house_items`. UUIDs are binary; timestamps are UTC; prices are integer minor units; serialized items use LONGBLOB. Indexes cover active ordering, price, seller, buyer, expiration, creation time, and owner claim storage. There are no player-name foreign keys or extra databases.

`auction.changed` identifies a listing and its state; `auction.item_claimed` identifies an item and owner. Events carry no serialized item payload. MariaDB remains authoritative and purchase locking never uses Redis. Auction views query MariaDB directly.

The EconomyService stable-ID transfer extension records both ledger sides atomically and replays a committed transaction rather than moving money twice. The Auction House never edits `economy_balances` directly and all prices remain `Money`/`Long` minor units.
