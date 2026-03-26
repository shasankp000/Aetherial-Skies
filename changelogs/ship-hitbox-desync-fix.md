# Ship Hitbox Desync + "Can't Keep Up" Fix

## Two Remaining Problems

### Problem 1: The small boat hitbox visually lags behind the rendered hull

What you're seeing with F3+B is the **client-side interpolated position** of the entity's AABB. The AABB follows the entity's position, which is synced from server to client based on `trackedUpdateRate`. Your ship is registered with `trackedUpdateRate(3)`, meaning the server sends a position update to the client every 3 ticks (150ms). Between updates, the client interpolates (smoothly slides) the entity toward the last known position.

But the rendered blocks (from `ShipBoatEntityRenderer`) are drawn at the entity's **interpolated render position** using `entity.getX()/getY()/getZ()`, while the wireframe hitbox shown by F3+B displays the entity's **actual AABB** which moves in discrete 3-tick jumps. This creates the visible offset between the rendered hull and the wireframe.

The vanilla boat uses `trackedUpdateRate(3)` too, but you don't notice it because the vanilla boat is tiny. With a large ship, the visual gap between the interpolated render position and the AABB position is amplified.

**Fix: Set `trackedUpdateRate(1)`** for the ship boat entity. This makes the server send position updates every tick, so the client AABB closely follows the rendered position.

```java
// In ModEntityTypes.java:
public static final EntityType<ShipBoatEntity> SHIP_BOAT_ENTITY =
        Registry.register(
                Registries.ENTITY_TYPE,
                new Identifier(AetherialSkies.MOD_ID, "ship_boat"),
                FabricEntityTypeBuilder.<ShipBoatEntity>create(SpawnGroup.MISC, ShipBoatEntity::new)
                        .dimensions(EntityDimensions.changing(1.375f, 0.5625f))
                        .trackRangeBlocks(128)
                        .trackedUpdateRate(1)   // was 3 → now 1 (every tick)
                        .build()
        );
```

**Also for collision parts**, set `trackedUpdateRate(1)` so they stay perfectly in sync:
```java
public static final EntityType<ShipCollisionPartEntity> SHIP_COLLISION_PART_ENTITY =
        Registry.register(
                Registries.ENTITY_TYPE,
                new Identifier(AetherialSkies.MOD_ID, "ship_collision_part"),
                FabricEntityTypeBuilder.<ShipCollisionPartEntity>create(SpawnGroup.MISC, ShipCollisionPartEntity::new)
                        .dimensions(EntityDimensions.fixed(1.0f, 1.0f))
                        .trackRangeBlocks(64)
                        .trackedUpdateRate(1)   // was 2 → now 1
                        .build()
        );
```

This has a minor network cost (more position packets), but for a single ship entity + a handful of collision parts, this is negligible. Vanilla minecarts use `trackedUpdateRate(1)` for exactly this reason — fast-moving vehicles need per-tick sync.

---

### Problem 2: "Can't keep up" warnings (2036ms + 20320ms behind)

The 20-second lag spike is catastrophic. Looking at the log: the first warning is 2036ms (40 ticks behind) and the second is **20320ms (406 ticks behind)** — that means the server froze for 20 seconds. This almost certainly happens during `rebuildLayer()` which gets triggered by `ensureLayer()`.

Here's the chain of events causing it:

1. On `ensureLayer()` (every 20 ticks), it calls `world.getEntitiesByClass()` with a **96-block radius** search box. That's a `192×192×192` block volume — **7 million cubic blocks** scanned for entities.

2. If the count doesn't match, it calls `rebuildLayer()` which:
   - Calls `removeLayer()` → another `getEntitiesByClass()` over 96-block radius
   - Then spawns new entities via `world.spawnEntity()` for each layer

3. On world load or after the first `ensureLayer()`, the count might not match (parts haven't loaded yet or got discarded), triggering a rebuild. The rebuild discards old parts and spawns new ones. But the new parts spawn into the same search area, and on the next check 20 ticks later, counts might still mismatch if parts were discarded during the rebuild (race condition). This can cause a **rebuild loop**.

**Fixes:**

#### A. Reduce search radius dramatically
The collision parts are always within a few blocks of the ship. A 96-block search radius is absurd overkill.

```java
// In ShipCollisionLayerService:
private static final double SEARCH_RADIUS = 16.0D;  // was 96.0D
```

Similarly, in `ShipBoatEntity.remove()`:
```java
ShipCollisionLayerService.removeLayer(world, this.getShipId(), this.getBoundingBox().expand(16.0D));
// was expand(128.0D)
```

#### B. Throttle `ensureLayer()` to every 100 ticks instead of 20
Checking every second is excessive. Every 5 seconds is plenty — parts don't just vanish randomly.

```java
public static void ensureLayer(ShipBoatEntity ship) {
    if (!(ship.getWorld() instanceof ServerWorld world)) {
        return;
    }
    if (ship.age % 100 != 0) {   // was 20 → now 100 (every 5 seconds)
        return;
    }
    // ... rest unchanged
}
```

#### C. Guard against rebuild loops
Add a cooldown to prevent `rebuildLayer()` from being called more than once per several seconds:

```java
// In ShipBoatEntity, add:
private int lastRebuildAge = -200;  // cooldown tracker

public boolean canRebuildLayer() {
    if (this.age - lastRebuildAge < 100) {
        return false;  // Don't rebuild more than once per 5 seconds
    }
    lastRebuildAge = this.age;
    return true;
}
```

Then in `ShipCollisionLayerService.ensureLayer()`:
```java
if (existing.size() != expected) {
    if (ship.canRebuildLayer()) {
        rebuildLayer(ship);
    }
}
```

#### D. Move `applyHullBuoyancy()` to both client and server, or remove the server-only guard

Currently `applyHullBuoyancy()` only runs on the server (line 310-314 has the `!isClient()` guard). The hull buoyancy correction is applied server-side, then the corrected position is sent to the client with a delay (even at `trackedUpdateRate(1)`, there's still a 1-tick latency). For the smoothest feel, the buoyancy correction should also run on the client:

```java
@Override
public void tick() {
    super.tick();
    applyHullBuoyancy();  // Run on both sides for smooth visual
    if (!this.getWorld().isClient()) {
        ShipCollisionLayerService.ensureLayer(this);
        clampToTerrain();
    }
}
```

This means the client predicts the same buoyancy forces as the server, so there's no visible correction snap. The server remains authoritative — if client/server drift apart, the next position sync corrects it.

---

## Summary of All Changes

| File | Change | Why |
|------|--------|-----|
| `ModEntityTypes.java` | `trackedUpdateRate(1)` for both ship + collision parts | Eliminates hitbox visual desync |
| `ShipCollisionLayerService.java` | `SEARCH_RADIUS = 16.0D` (was 96) | 216x reduction in entity search volume |
| `ShipCollisionLayerService.java` | `ship.age % 100` (was 20) | 5x less frequent integrity checks |
| `ShipCollisionLayerService.ensureLayer()` | Add rebuild cooldown via `ship.canRebuildLayer()` | Prevents rebuild storm |
| `ShipBoatEntity.remove()` | `expand(16.0D)` (was 128) | Matches reduced search radius |
| `ShipBoatEntity.tick()` | Move `applyHullBuoyancy()` outside `!isClient()` guard | Client-side prediction for smooth buoyancy |
| `ShipBoatEntity` | Add `lastRebuildAge` + `canRebuildLayer()` | Cooldown for layer rebuilds |

These are all small, safe changes. The biggest impact comes from the search radius reduction (eliminates the 20-second freeze) and `trackedUpdateRate(1)` (eliminates the visible hitbox lag).
