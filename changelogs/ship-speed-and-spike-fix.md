# Ship Speed Reduction + Remaining Server Spikes — Final Fix

## Issue 1: Drastically Reduced Movement Speed

**Root cause**: `applyHullBuoyancy()` at line 226-227 applies `vel.x * 0.9, vel.z * 0.9` (10% horizontal drag) every tick whenever `submersionRatio > 0`. Since the ship is always in water, this fires every tick.

But here's the compounding problem — **vanilla `BoatEntity.tick()` already applies its own water drag** inside `floatBoat()` and `controlBoat()`. The vanilla boat already slows down naturally in water. Your `* 0.9` is stacking on top of vanilla's drag, so the effective drag per tick is approximately `vanilla_drag * 0.9`, which compounds exponentially. Over 20 ticks, vanilla drag alone might reduce speed to ~60% of input. With your extra 0.9 multiplier, it's `0.6 * 0.9^20 ≈ 0.6 * 0.12 = 7%` of input speed. That's why the ship feels dramatically slower.

**Fix**: Remove the horizontal drag entirely from `applyHullBuoyancy()`. Vanilla already handles water drag correctly. Your buoyancy method should only affect the Y-axis (vertical buoyancy correction):

```java
private void applyHullBuoyancy() {
    if (cachedBounds == null) {
        return;
    }

    ShipHullData hull = getHullData();
    float effectiveDensity = Math.max(0.05f, hull.effectiveRelativeDensity());
    float buoyancyAssist = hull.buoyancyAssist();
    float submersionRatio = calculateHullSubmersion();

    double buoyancyForce = 0.0D;

    if (submersionRatio > 0.0f) {
        float targetSubmersion = Math.min(0.9f, effectiveDensity);
        double displacement = submersionRatio - targetSubmersion;
        double restoring = displacement * 0.08D;
        double assistLift = buoyancyAssist * 0.015D;
        buoyancyForce = restoring + assistLift;

        // REMOVED: vel.x * 0.9D, vel.z * 0.9D — vanilla boat already handles water drag
    }

    Vec3d currentVel = this.getVelocity();
    this.setVelocity(currentVel.x, currentVel.y + buoyancyForce, currentVel.z);

    Vec3d updated = this.getVelocity();
    if (Math.abs(updated.y) < 0.003D) {
        this.setVelocity(updated.x, 0.0D, updated.z);
    } else {
        this.setVelocity(updated.x, updated.y * 0.92D, updated.z);
    }
}
```

---

## Issue 2: 25-Second Server Freeze (25016ms / 500 ticks behind)

The `resolveOwner()` fallback on `ShipCollisionPartEntity` line 241-245 still uses `expand(64.0D)`:

```java
List<ShipBoatEntity> owners = this.getWorld().getEntitiesByClass(
    ShipBoatEntity.class,
    this.getBoundingBox().expand(64.0D),   // ← THIS IS THE PROBLEM
    ship -> ownerShipId != null && ownerShipId.equals(ship.getShipId())
);
```

This scans a 128×128×128 block volume. Even though the fast path (cached reference or entity ID lookup) should almost always hit, on world load ALL collision parts start with `cachedOwner = null` and `ownerEntityId = -1` (entity IDs are not stable across saves). So on the first tick after loading, EVERY collision part falls through to this expensive search.

For a ship with even 1 collision layer, that's 1 entity doing a 128³ search. But the timing suggests something worse — the cooldown is only 10 ticks, and if the `getEntityById()` path keeps failing (because entity IDs changed after reload), every 10 ticks each part does a full scan.

**Fix**: Reduce the search radius to 16 and increase the cooldown:

```java
private ShipBoatEntity resolveOwner() {
    // Fast path: cached reference
    if (cachedOwner != null
            && !cachedOwner.isRemoved()
            && ownerShipId != null
            && ownerShipId.equals(cachedOwner.getShipId())) {
        return cachedOwner;
    }

    // Medium path: entity ID lookup (instant, no search)
    if (ownerEntityId >= 0) {
        Entity byId = this.getWorld().getEntityById(ownerEntityId);
        if (byId instanceof ShipBoatEntity ship
                && ownerShipId != null
                && ownerShipId.equals(ship.getShipId())) {
            cachedOwner = ship;
            return ship;
        }
    }

    // Slow path: area search — throttle heavily
    if (ownerSearchCooldown > 0) {
        ownerSearchCooldown--;
        return null;
    }
    ownerSearchCooldown = 100;  // was 10 → now 100 (search at most every 5 seconds)

    List<ShipBoatEntity> owners = this.getWorld().getEntitiesByClass(
            ShipBoatEntity.class,
            this.getBoundingBox().expand(16.0D),  // was 64 → now 16
            ship -> ownerShipId != null && ownerShipId.equals(ship.getShipId())
    );

    if (!owners.isEmpty()) {
        ShipBoatEntity owner = owners.get(0);
        cachedOwner = owner;
        ownerEntityId = owner.getId();
        return owner;
    }

    return null;
}
```

Note: the search cooldown was also placed BEFORE the entity ID check in your code. Move it to be AFTER the entity ID check (as shown above), so the fast O(1) lookup always runs, and only the expensive area search gets throttled.

---

## Issue 3: Boat hitbox still slightly lagging behind

Even with `trackedUpdateRate(1)`, there will always be a 1-tick latency between server position and client AABB because the server computes the position, sends the packet, and the client applies it next frame. This is inherent to Minecraft's entity networking.

The vanilla boat has the same 1-tick desync, but it's invisible because the boat is 1.375 blocks wide. On your 5×5 ship, a 1-tick position delta of ~0.1 blocks is visible against the large hull.

The only way to fully eliminate this is to **also run the boat's movement prediction on the client**. You're already running `applyHullBuoyancy()` on the client (after the `super.tick()` change). But the collision part positions are only updated server-side (their `tick()` has `if (isClient()) return;`). 

For the hitbox wireframe specifically: the client AABB is always set by the server via tracked position packets. You can't change that without a mixin on the entity tracker. This is a cosmetic issue visible only with F3+B — players won't notice it during normal gameplay. I'd recommend treating this as acceptable for now and focusing on the functional issues.

---

## Summary of Changes

| File | Line | Change | Effect |
|------|------|--------|--------|
| `ShipBoatEntity.java` | 226-227 | Remove `vel.x * 0.9D` / `vel.z * 0.9D` horizontal drag | Restores normal ship movement speed |
| `ShipCollisionPartEntity.java` | 229 | `ownerSearchCooldown = 100` (was 10) | Reduces expensive search frequency 10x |
| `ShipCollisionPartEntity.java` | 243 | `expand(16.0D)` (was 64) | 64x smaller search volume |
| `ShipCollisionPartEntity.java` | 225-228 | Move cooldown check AFTER the `getEntityById` fast path | Entity ID lookup always runs; only area search is throttled |
