# Persistent Bank

A RuneLite plugin that snapshots your bank, inventory, equipment, seed vault
and Grand Exchange slots to disk on a rate-limited cadence. An external
application (RSVault) watches the snapshot folder and shows a combined
bank view across every Jagex account you log into.

## Compliance

The plugin is strictly observational:

- No input synthesis. It never clicks, types, or moves the camera.
- No packet modification.
- No changes to game behavior.
- It only reads `Client` state and writes JSON files to disk.

This keeps it squarely inside the Jagex client-rules envelope for RuneLite
plugins.

## Wealth panel

The plugin adds a side-panel button ("Persistent Bank") showing a running
wealth total across every Jagex account you've logged in with on this
machine. Each row shows the account's display name, current GE value, and
how long since it was last snapshotted. Values are precomputed at snapshot
time from RuneLite's price cache and cached into the JSON, so opening the
panel is effectively free — no price lookups, no item iteration.

## How it works

Once enabled, the plugin subscribes to container-changed events and writes
one JSON file per Jagex account to `<RuneLite dir>/persistent-bank/<accountHash>.json`
(override the folder in plugin config).

Writes are rate-limited: after any change it checks when the last write for
that account happened and either flushes immediately (if the cooldown has
elapsed) or schedules a deferred flush. Default cooldown is 15 minutes, so
bank-standing skills that open/close the bank hundreds of times per hour
still produce at most 4 writes per hour per account.

On logout (if enabled in config) the plugin flushes any pending changes
so short sessions don't get lost.

## Snapshot schema (v1)

```jsonc
{
  "schemaVersion": 1,
  "accountHash": 1234567890123,
  "displayName": "Zezima",
  "lastUpdated": 1745000000000,   // epoch ms at write time
  "totalValueGp": 842193217,      // sum of the four container section totals below

  "bank":        { "updatedAt": 1745000000000, "totalValueGp": 800000000, "items": [{ "id": 995, "qty": 12345678 }, ...] },
  "inventory":   { "updatedAt": 1745000000000, "totalValueGp":   2193217, "items": [{ "id": 1323, "qty": 1 }, ...] },
  "equipment":   { "updatedAt": 1745000000000, "totalValueGp":  40000000, "items": [{ "id": 4587, "qty": 1, "slot": "WEAPON" }, ...] },
  "seedVault":   { "updatedAt": 1745000000000, "totalValueGp":         0, "items": [...] },
  "grandExchange": {
    "updatedAt": 1745000000000,
    "slots": [
      { "slot": 0, "state": "BUYING",  "itemId": 4151, "qtySought": 1, "qtyTraded": 0, "pricePer": 1800000, "spent": 0 },
      { "slot": 1, "state": "EMPTY" },
      // ...up to 8 slots
    ]
  }
}
```

### Notes for readers

- **Absent sections** mean "not observed this session." Never treat a
  missing section as empty — use the last written snapshot instead.
- **Empty slots** are omitted from `items` arrays. Bank/inventory/seed-vault
  containers are flat lists, not padded to a fixed size.
- **`totalValueGp`** is computed at write time from RuneLite's ItemManager
  GE price cache (`getItemPrice(id) * qty`, summed). Untradeable items and
  prices the client can't resolve contribute zero. The top-level
  `totalValueGp` deliberately excludes Grand Exchange slots to avoid double-
  counting coins the player also has banked; walk the `grandExchange.slots`
  array directly if you want to include in-flight GE value.
- **Noted items** keep RuneLite's raw item id. If you need to collapse
  noted-vs-unnoted into a single row, map with the wiki item-mapping API.
- **GE `slot`** is always 0..7; empty slots render as `{ "slot": N, "state": "EMPTY" }`.

## Config

- **What to snapshot:** per-container toggles (bank / inventory / equipment / seed vault / GE).
- **Minimum minutes between snapshots:** default 15, range 1–120.
- **Flush on logout:** default ON.
- **First snapshot immediate:** default ON. New accounts get written
  immediately instead of waiting for the cooldown.
- **Snapshot folder override:** leave blank for `<RuneLite>/persistent-bank`,
  or set to any absolute path RSVault is watching.
- **Verbose logging:** log every write for debugging.

## Building (developer notes)

Standard RuneLite external-plugin build:

```
./gradlew build
```

The compiled jar is in `build/libs/`. To iterate, clone the
[runelite/example-plugin](https://github.com/runelite/example-plugin)
template, drop these sources over the `example` package, and run RuneLite
from IntelliJ as described in the RuneLite developer docs.

## Submitting to the Plugin Hub

Fork `runelite/plugin-hub`, add a manifest pointing at this repo + the
commit you want released, and open a PR. See the
[Plugin Hub readme](https://github.com/runelite/plugin-hub#submitting-your-plugin)
for the exact template.
