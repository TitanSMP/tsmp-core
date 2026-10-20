# TitanSMP Admin Shop

`/shop` opens the native Paper inventory shop. Shop content is configured in `config/titansmp.properties`; no GUI plugin or second currency is used. The example configuration starts with Blocks, Nether, End, Food, and Gear. It contains no shard, spawner, or shard-currency entries.

## Configuration

Shop properties use IDs as property-key segments. Category and item IDs must be lowercase identifiers and unique. Category slots and item slots are unique within their menus and use the first 45 slots. Item slots are paginated in groups of 45. Prices use exact `Money` minor units; negative, malformed, over-precision, or overflow-prone prices fail startup. `0.00` means free. The literal `null` disables that direction.

```properties
shop.enabled=true
shop.categories.food.display-name=Food
shop.categories.food.icon=COOKED_BEEF
shop.categories.food.slot=23
shop.categories.food.enabled=true
shop.categories.food.items.steak.material=COOKED_BEEF
shop.categories.food.items.steak.amount=16
shop.categories.food.items.steak.display-name=Steak
shop.categories.food.items.steak.slot=10
shop.categories.food.items.steak.buy-price=100.00
shop.categories.food.items.steak.sell-price=50.00
```

Materials are validated against the running Paper API before registration. Amount is the pack size. The quantity picker offers 1, 16, 32, or 64 packs and a separate confirmation step; resulting items are split at the material's stack limit. Item counts and prices are multiplied by the chosen pack count.

## Buying and selling

The item tooltip shows the configured buy and sell prices. Each direction is independently disabled by a `null` price. Buyers need sufficient inventory space and TitanCoins; sellers must have the complete selected quantity. Purchases debit with `SHOP_PURCHASE` before delivery. If delivery cannot complete, the module credits a refund with the same audit reason. Sales remove the full quantity before crediting with `SHOP_SELL`; failed credits restore the removed items. Shop inventory interactions are blocked while a local transaction is running.

The economy ledger and balance remain owned by the existing EconomyService and MariaDB economy tables. The shop does not add a balance table or Redis balance. This uses a compensating transaction across the economy database operation and local Paper inventory because those resources cannot share a single transaction. A process crash in the narrow interval between a committed economy operation and inventory delivery/compensation is not journal-replayed by this first version.

## Category state and network updates

Category definitions, materials, prices, icons, and slots remain config data. MariaDB stores only category enablement in `shop_category_state`; missing rows mean enabled. Changes are serialized with a row lock and committed together with an audit row containing old/new values, admin UUID, time, and source server. Only after commit does `ShopCategoryStateChanged` publish through the shared Redis Event Bus. Redis failure is reported as a committed DB change with delayed peer refresh, not as a network-wide success. Each shop open and transaction checks MariaDB, so peers reconcile from the source of truth even if an event was missed. Events update the in-process cache and refresh already open shop views.

Admin commands require the LuckPerms permission `titansmp.shop.admin`:

```text
/shopadmin category <category> enable
/shopadmin category <category> disable
/shopadmin category <category> status
/shopadmin categories
```

`/shop` requires `titansmp.shop.use`. Disabled categories keep their configured main-menu slot and show a Barrier with `<Category> - Disabled`; opening and every purchase/sale are rejected server-side after a MariaDB state check.
