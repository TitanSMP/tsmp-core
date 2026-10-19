# Map Synchronization Analysis

## Scope and decision

This phase keeps map data out of `PlayerState`. The shared player snapshot already stores inventory items through Paper's opaque item codec. That is sufficient to preserve the serialized map item and its reference, but it does not copy the server-side map asset that the reference resolves to. Paper 26.3 exposes no supported complete export/import API for vanilla map pixel state. TitanSMP therefore does not claim cross-server filled-map content synchronization yet and does not create a partial map registry or a map-specific transfer protocol.

This is a deliberate safe boundary: item bytes can be saved and restored, but a target server must already have the same logical map registered under a usable identity for the displayed image to match. If this precondition is not met, setting a raw ID on an unrelated target map may bind the item to the wrong map or cause server behavior to create a different map. The current implementation does not rewrite map IDs.

## Paper 26.3 API findings

The project compiles against `io.papermc.paper:paper-api:26.3.build.205-stable`. The official Paper 26.3 `MapMeta` API exposes `hasMapId`, `getMapId`, `hasMapView`, `getMapView`, `setMapView`, and `isScaling`/`setScaling`. Paper explicitly marks the integer `setMapId` route as deprecated and warns that invalid IDs have poorly defined behavior and may result in a new map ID. A `MapView` reference can be absent on a `MapMeta` that claims to have one if its associated map does not exist on the server. See [Paper MapMeta](https://jd.papermc.io/paper/26.3/org/bukkit/inventory/meta/MapMeta.html).

`MapView` exposes an integer ID, center X/Z, world, one of the map scales, locked status, position tracking, unlimited tracking, and its active renderer list. It also allows adding/removing renderers and changing those view properties. Its ID is described as the ID of a map item for use with `MapMeta`; the API does not define a network-wide namespace or identity. The integer is therefore only meaningful in the server's map registry context and must not be treated as a TitanSMP-global ID. See [Paper MapView](https://jd.papermc.io/paper/26.3/org/bukkit/map/MapView.html).

`MapCanvas` is a renderer layer and supports pixel reads/writes, drawing, and cursors. It is passed to `MapRenderer.render(MapView, MapCanvas, Player)`; the canvas is associated with a particular renderer. The public API has no operation that exports the complete rendered 128×128 final image, all saved renderer-layer state, or the underlying vanilla map data as an importable asset. Pixel access is renderer-layer based, so reading a layer is not equivalent to extracting the final displayed image. See [Paper MapCanvas](https://jd.papermc.io/paper/26.3/org/bukkit/map/MapCanvas.html) and [Paper MapRenderer](https://jd.papermc.io/paper/26.1.2/org/bukkit/map/MapRenderer.html).

Paper's item data component API also exposes `MapId`, which describes a reference to shared map state holding map contents and markers, and `MapDecorations`, which holds marker entries. This reinforces that a filled-map item can carry a reference and decoration-related data while the map's rendered content remains shared state outside the player's inventory. These item component interfaces are version-dependent API and are not used to invent a cross-server map identity in this phase. See [Paper MapId](https://jd.papermc.io/paper/26.3/io/papermc/paper/datacomponent/item/MapId.html) and [Paper MapDecorations](https://jd.papermc.io/paper/26.3/io/papermc/paper/datacomponent/item/MapDecorations.html).

## Item versus map asset

| Data | Scope | Current handling |
| --- | --- | --- |
| Map item material, amount, item data, and serialized metadata/reference | Player inventory item | Stored in the existing Paper `ItemStack.serializeAsBytes` payload, within the normal PlayerState inventory slots |
| Empty-map state before it has a map reference | Player inventory item | Serialized as ordinary item data; no map asset is added to PlayerState |
| Filled-map ID/reference | Item reference to shared server map state | Preserved opaquely by item serialization; integer is not assumed network-global and is never rewritten |
| Map center, world, scale, lock, and tracking flags | `MapView` / server map state | Owned by the server registry; not player-specific and not separately synchronized |
| Vanilla pixel contents and live map updates | Shared map asset / world-server state | No complete supported Paper export/import path is available here; not copied into PlayerState or MariaDB |
| Map decorations/markers | Shared map state and, for supported item components such as explorer map decoration data, item data | Preserved only insofar as Paper item serialization preserves item components; server map state is not replicated |
| Renderer instances and renderer-owned canvas layers | Plugin/server runtime | Not serialized. Plugin objects/classes are never stored in MariaDB |
| Position cursor visibility for a viewing player | Viewer/render context and map tracking configuration | Map tracking flags are server map state; per-viewer runtime output is not part of PlayerState |

Normal empty maps are only item stacks until they acquire a map reference. Filled maps refer to a `MapView`/shared map state. Scale, lock, tracking, center, and world are properties of that view, not of the player. No player-specific map ownership state is exposed by these APIs. Map copies should retain the same reference; this system preserves the serialized item reference and does not make per-item clones of map assets.

The API presents maps as server-managed map views and Paper/Bukkit expose creation and lookup against that server's map registry. The integer ID is not specified as globally unique across independently provisioned servers or worlds. Thus identical numeric IDs on SMP-01 and SMP-02 are not proof of identical map content. TitanSMP currently has no network map identity mapping.

## Persistence and proposed asset boundary

Native Java Edition map data is persisted with world saved data (commonly represented by `data/map_<id>.dat`, with map ID allocation metadata alongside it); see the [map item format reference](https://minecraft.wiki/w/Map_item_format). The map ID and asset therefore belong to world/server state shared by all players using that registry, rather than to a particular player's inventory. Sharing the corresponding world map registry consistently between backends may be viable only where the servers intentionally share compatible world data and operate on it safely; TitanSMP does not assume that topology. The Paper API does not provide an asset export/import contract that would allow copying this registry state safely between independent worlds.

If TitanSMP later requires maps to work across independently stored worlds, treat the image and its stable network identity as a network-wide map asset, separate from the player inventory. The player payload should continue to contain only the item reference. A future asset store could use MariaDB binary data or an object/file asset store, but a schema, payload, or service should be selected only after defining supported map sources, renderer semantics, size bounds, update ownership, and how a target resolves an asset into its local map registry. No asset table or extra infrastructure is introduced now.

Map updates do not write to MariaDB through this Data Bridge. There is no pixel event listener, per-pixel write, tick save, dirty tracking, or independent map transfer. If a future TitanSMP-owned renderer/asset format is introduced, it will need batched asset persistence independent from player transfer; player transfer should only wait for an asset when a concrete reference resolution contract requires it.

## Renderer and plugin compatibility

Paper reports active `MapRenderer` instances and documents a map as virtual when its lowest renderer is plugin-provided. Renderer classes, plugin state, and a renderer's canvas layers cannot be made portable by serializing an `ItemStack` or the renderer object. A target may not have the same plugin, renderer version, per-player render context, or backing data. TitanSMP does not attempt to detect arbitrary plugin renderer compatibility or replicate custom renderer state. Custom maps are outside the supported synchronization contract.

Vanilla maps also depend on the target resolving the reference to compatible server map data. Item serialization alone is not an end-to-end guarantee for the image. Locked, tracking, scale, and decoration item/view metadata may round-trip as part of the serialized item or remain in local map state, but this does not make the referenced asset available on another backend.

## Existing item and transfer path

Map items stay in the ordinary inventory path:

```text
Paper ItemStack
  -> PaperItemStackCodec.serializeAsBytes
  -> versioned PlayerState payload / MariaDB
  -> PaperItemStackCodec.deserializeBytes
  -> target inventory
```

There is no second map item serializer, map field in the API DTO, payload version bump, or map-specific transfer service. Existing V1/V2/V3 payload compatibility is unchanged. If a decoded item has a map ID but Paper cannot resolve its `MapView`, the loader keeps the item and logs the unavailable local ID once per player load. It does not invent a replacement or remap the item, because that could silently associate it with an unrelated map. The original serialized bytes remain in MariaDB, allowing recovery after the target's map registry is corrected.

## Verification and limitations

The current unit test round-trips a filled-map item with ordinary `MapMeta` through the shared codec. MockBukkit does not implement the `MapView` mutations needed to configure a realistic registered map; it also cannot serialize its `MapViewMock` object when embedded in item metadata. The test therefore does not claim to cover a map ID, locked state, renderer output, or pixel content. It validates only the safe item codec path that MockBukkit supports. A full identity/lock/content transfer test requires real Paper integration servers and a defined network asset mechanism.

No NMS, reflection, direct world-file editing, internal map format assumptions, MariaDB map payloads, or custom-renderer serialization are used. Statistics remain outside this phase.
