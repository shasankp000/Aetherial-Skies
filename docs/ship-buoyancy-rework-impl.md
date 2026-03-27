# Ship Buoyancy Density Rework — Implementation Design Document

> **For GitHub Copilot:** This document is the authoritative specification for the ship buoyancy and collision architecture overhaul. Follow every section in order. Do not deviate from the design unless a section explicitly says "Copilot may choose implementation detail." All file paths are relative to the project root. The target branch is `1.20.1`.

---

## 0. Context & Goals

### Current State (What Is Broken)

The ship system currently uses two entities working in tandem:

1. **`ShipBoatEntity`** (`src/main/java/net/shasankp000/Entity/ShipBoatEntity.java`) — A `BoatEntity` subclass intentionally kept at **vanilla hitbox size** (`1.375f × 0.5625f`). It runs a corrective `applyHullBuoyancy()` pass *on top of* `super.tick()` (vanilla boat physics), meaning two competing buoyancy systems fight each other every tick. It also runs a per-block submersion loop (`calculateHullSubmersion()`) every 3 ticks — expensive for large ships.

2. **`ShipCollisionPartEntity`** (`src/main/java/net/shasankp000/Entity/ShipCollisionPartEntity.java`) — Ghost entities spawned per-layer around the hull to simulate deck solidity. Currently **disabled** (`isCollidable()` returns `false`). Despite being disabled, every instance still ticks, runs `resolveOwner()` (entity search up to 16 blocks), and syncs tracked data to clients every tick. This is the primary source of server spikes.

### Target State (What We Are Building)

- **Delete `ShipCollisionPartEntity` entirely.** It is disabled and a performance liability.
- **Expand `ShipBoatEntity`'s AABB** to the full ship hull dimensions so the player stands on the real bounding box — no ghost entities needed.
- **Suppress vanilla `BoatEntity` buoyancy completely** via a Mixin and replace it with a single, self-contained density-based buoyancy model inside `ShipPhysicsEngine`.
- **Wire `ShipPhysicsEngine`** as the sole vertical physics authority for `ShipBoatEntity`.

### Two Problems This Solves

| Problem | Root Cause | Fix |
|---|---|---|
| Player falls through deck | Collision parts disabled, real hitbox is vanilla-tiny | Expand hitbox to full hull dimensions |
| Server spikes / lag | `ShipCollisionPartEntity` ticking + dual buoyancy fight | Delete collision parts, suppress vanilla buoyancy, single physics pass |

---

## 1. Physics Model — Density-Based Buoyancy

### 1.1 Definitions

| Symbol | Meaning | Where It Lives |
|---|---|---|
| `ρ_ship` | Ship's effective relative density (`effectiveRelativeDensity` field) | `ShipHullData` record |
| `ρ_water` | Water density (normalized to `1.0f`) | Constant in `PhysicsConfig` |
| `V_hull` | Total displaced volume of the hull (`totalDisplacedVolume` field, in cubic blocks) | `ShipHullData` record |
| `V_sub` | Volume of hull currently submerged (computed per tick) | Computed in `ShipPhysicsEngine` |
| `F_b` | Net buoyancy force (upward, in game units/tick²) | Output of buoyancy step |

### 1.2 The Buoyancy Rule

The ship floats at equilibrium when:

```
V_sub / V_hull == ρ_ship / ρ_water
```

Since `ρ_water = 1.0`, this simplifies to:

```
V_sub / V_hull == ρ_ship
```

i.e., the fraction of the hull that must be submerged equals the ship's relative density. A ship with `ρ_ship = 0.5` floats with exactly half its volume submerged.

### 1.3 Restoring Force Formula

Each tick, compute the **submersion ratio** `s = V_sub / V_hull` (range 0.0–1.0), then:

```
displacement_error = s - ρ_ship          // positive = too deep, negative = too high
F_b = -displacement_error * BUOYANCY_STIFFNESS
```

Apply `F_b` directly to `state.velocity.y`. This is a spring-damper model — the ship oscillates toward equilibrium and settles.

### 1.4 Submersion Computation (Optimized)

**Do not** iterate over every `PackedBlock` every tick. Instead:

1. At hull load time (inside `ShipBoatEntity.recalculateDimensions()`), compute and cache:
   - `hullBottomY` = `cachedBounds.minY()` (local offset from entity origin)
   - `hullHeight` = `cachedBounds.height()`
   - `hullVolume` = `totalDisplacedVolume` from `ShipHullData` (already stored)

2. Each tick, compute the **water surface Y** at the ship's XZ position by scanning upward from `hullBottomY` until the fluid state is empty. Cache this for 5 ticks (`WATER_SURFACE_CACHE_TICKS = 5`).

3. Compute:
   ```java
   double submergedDepth = clamp(waterSurfaceY - (shipY + hullBottomY), 0, hullHeight);
   float s = (float)(submergedDepth / hullHeight);  // submersion ratio
   ```

This replaces the entire per-block scan with two world reads (one fluid scan upward, max ~5 blocks). **O(1) per tick vs. O(N) per 3 ticks.**

---

## 2. Files to Delete

### 2.1 `ShipCollisionPartEntity.java`

**Delete this file entirely:**
```
src/main/java/net/shasankp000/Entity/ShipCollisionPartEntity.java
```

**Also remove:**
- Any registration of `SHIP_COLLISION_PART_ENTITY` in `src/main/java/net/shasankp000/Registry/ModEntityTypes.java`
- Any registration of its renderer in the client entrypoint (search for `ShipCollisionPartEntity` across the codebase)
- Any spawn calls in `ShipCollisionLayerService.java` — see Section 5

### 2.2 `ShipCollisionLayerService.java`

**Delete this file entirely:**
```
src/main/java/net/shasankp000/Ship/ShipCollisionLayerService.java
```

It exists solely to spawn and manage `ShipCollisionPartEntity` instances. With those gone, this service has no purpose.

---

## 3. `ShipBoatEntity.java` — Full Rewrite Specification

File: `src/main/java/net/shasankp000/Entity/ShipBoatEntity.java`

### 3.1 What to Keep (Unchanged)

- All `TrackedData` fields (`TRACKED_SHIP_ID`, `TRACKED_HULL_TAG`)
- `shipId`, `hullTag`, `cachedHullData` fields
- `getShipId()`, `getHullData()`, `setHullData()`, `getExpectedLayerCount()`, `canRebuildLayer()` methods
- `initDataTracker()`, `onTrackedDataSet()`, `writeCustomDataToNbt()`, `readCustomDataFromNbt()` methods
- `updatePassengerPosition()` method (helm rider positioning)
- `collidesWith()` override (keep the self-collision guard, just remove the `ShipCollisionPartEntity` branch)

### 3.2 What to Remove

- `cachedSubmersion`, `submersionCheckCooldown` fields — replaced by `ShipPhysicsEngine`
- `applyHullBuoyancy()` method — replaced by `ShipPhysicsEngine`
- `calculateHullSubmersion()` method — replaced by `ShipPhysicsEngine`
- `clampToTerrain()` method — `ShipPhysicsEngine.tick()` already handles terrain collision
- `waterlineOffset` field — no longer needed
- The `lastRebuildAge` field and `canRebuildLayer()` method **only if** no other code references them; otherwise keep.

### 3.3 What to Add

#### 3.3.1 New Fields

```java
private ShipPhysicsEngine physicsEngine = null;
private EntityDimensions cachedDimensions = EntityDimensions.changing(1.375f, 0.5625f);
private ShipHullData.HullBounds cachedBounds = null;
```

#### 3.3.2 `recalculateDimensions()` — New Version

```java
private void recalculateDimensions() {
    ShipHullData hull = getHullData();
    if (hull == null || hull.blocks().isEmpty()) {
        cachedDimensions = EntityDimensions.changing(1.375f, 0.5625f);
        cachedBounds = null;
        physicsEngine = null;
        this.calculateDimensions();
        return;
    }

    cachedBounds = hull.computeBounds();

    // Expand the AABB to the full ship hull so the player stands on the real hitbox.
    float width  = (float) Math.max(cachedBounds.widthX(), cachedBounds.widthZ());
    float height = (float) cachedBounds.height();
    cachedDimensions = EntityDimensions.changing(width, height);

    // Rebuild the physics engine with updated hull data.
    ShipPhysicsState newState = new ShipPhysicsState();
    newState.position = this.getPos();
    newState.velocity = this.getVelocity();
    newState.yaw      = this.getYaw();
    newState.mass     = hull.mass();

    physicsEngine = new ShipPhysicsEngine(newState, this.getWorld(), hull.totalDisplacedVolume());
    physicsEngine.setHullBounds(cachedBounds);
    physicsEngine.setHullData(hull);

    this.calculateDimensions();
}
```

#### 3.3.3 `getDimensions()` Override

```java
@Override
public EntityDimensions getDimensions(EntityPose pose) {
    return cachedDimensions;
}
```

#### 3.3.4 `tick()` — New Version

```java
@Override
public void tick() {
    // Step 1: Suppress vanilla BoatEntity buoyancy and movement by calling
    // Entity.tick() (grandparent), NOT BoatEntity.super.tick().
    // This is done via the BoatEntityMixin — see Section 4.
    // Here we just call super.tick() which the Mixin will intercept.
    super.tick();

    // Step 2: Run our physics engine as the sole authority on vertical motion.
    if (!this.getWorld().isClient() && physicsEngine != null) {
        physicsEngine.syncFrom(this);   // pull current pos/vel from entity
        physicsEngine.tick();            // run one physics step
        physicsEngine.applyTo(this);     // push new pos/vel back to entity
    }
}
```

#### 3.3.5 `collidesWith()` — Simplified

```java
@Override
public boolean collidesWith(Entity other) {
    // ShipCollisionPartEntity is deleted — nothing to exclude here anymore.
    return super.collidesWith(other);
}
```

---

## 4. Mixin — Suppress Vanilla `BoatEntity` Buoyancy

### 4.1 Why This Is Necessary

`BoatEntity.tick()` in Minecraft 1.20.1 calls an internal method (`checkBoatInWater()` or equivalent) that applies its own fixed buoyancy, gravity, and drag every tick. If we call `super.tick()` from `ShipBoatEntity`, this runs **before** our physics engine and corrupts the velocity we compute. We must silence it for `ShipBoatEntity` specifically.

### 4.2 Mixin File to Create

Create: `src/main/java/net/shasankp000/mixin/BoatEntityMixin.java`

```java
package net.shasankp000.mixin;

import net.minecraft.entity.vehicle.BoatEntity;
import net.shasankp000.Entity.ShipBoatEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.callback.CallbackInfo;

@Mixin(BoatEntity.class)
public class BoatEntityMixin {

    /**
     * Cancel the vanilla BoatEntity tick for ShipBoatEntity instances.
     * ShipBoatEntity.tick() will call Entity.tick() (via a direct invoke mixin or
     * by overriding the full tick body), then hand off to ShipPhysicsEngine.
     *
     * The injection target is the HEAD of BoatEntity.tick(). We cancel only when
     * the instance is a ShipBoatEntity so vanilla boats are completely unaffected.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void aetherial_suppressVanillaTickForShip(CallbackInfo ci) {
        if ((Object) this instanceof ShipBoatEntity) {
            ci.cancel();
        }
    }
}
```

### 4.3 Register the Mixin

In `src/main/resources/aetherial_skies.mixins.json` (or your project's mixin config file), add `"BoatEntityMixin"` to the `"mixins"` array.

### 4.4 What `ShipBoatEntity.tick()` Must Now Call Instead

Since we cancelled `BoatEntity.tick()`, `ShipBoatEntity` must manually invoke the base `Entity.tick()` to keep tracking, passenger updates, and age increments working. The cleanest way is:

Add a second mixin or accessor interface that exposes `Entity.tick()` as `entityTick()` on `ShipBoatEntity`, **or** simply restructure `ShipBoatEntity.tick()` to duplicate the minimal `Entity.tick()` calls needed (age increment, passenger position update, fire tick). 

**Recommended approach — add an `@Invoker` mixin accessor:**

Create: `src/main/java/net/shasankp000/mixin/EntityTickInvoker.java`

```java
package net.shasankp000.mixin;

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Entity.class)
public interface EntityTickInvoker {
    @Invoker("tick")
    void invokeEntityTick();
}
```

Then in `ShipBoatEntity.tick()`:

```java
@Override
public void tick() {
    // Call the grandparent Entity.tick() directly, bypassing BoatEntity.tick()
    // which has been suppressed by BoatEntityMixin.
    ((EntityTickInvoker) this).invokeEntityTick();

    // Run ship-specific passenger updates (helm rider positioning).
    this.updatePassengerPositions();

    // Server-only: run our physics engine.
    if (!this.getWorld().isClient() && physicsEngine != null) {
        physicsEngine.syncFrom(this);
        physicsEngine.tick();
        physicsEngine.applyTo(this);
    }
}
```

Register `EntityTickInvoker` in the mixin config as well.

---

## 5. `ShipPhysicsEngine.java` — Required Changes

File: `src/main/java/net/shasankp000/Ship/Physics/ShipPhysicsEngine.java`

The existing `ShipPhysicsEngine` has a `calculateBuoyancy()` method that uses a fixed 3×3×3 bounding box and hardcoded block count. **Replace this entirely** with the density model from Section 1.

### 5.1 New Fields to Add

```java
private ShipHullData.HullBounds hullBounds = null;
private ShipHullData hullData = null;

// Water surface cache
private double cachedWaterSurfaceY = Double.NaN;
private int waterSurfaceCacheTicks = 0;
private static final int WATER_SURFACE_CACHE_TICKS = 5;
```

### 5.2 New Setter Methods

```java
public void setHullBounds(ShipHullData.HullBounds bounds) {
    this.hullBounds = bounds;
}

public void setHullData(ShipHullData data) {
    this.hullData = data;
}
```

### 5.3 `syncFrom(ShipBoatEntity ship)` — New Method

```java
public void syncFrom(ShipBoatEntity ship) {
    state.position = ship.getPos();
    state.velocity = ship.getVelocity();
    state.yaw      = ship.getYaw();
}
```

### 5.4 `applyTo(ShipBoatEntity ship)` — New Method

```java
public void applyTo(ShipBoatEntity ship) {
    ship.setPosition(state.position.x, state.position.y, state.position.z);
    ship.setVelocity(state.velocity);
}
```

### 5.5 Replace `calculateBuoyancy()` — New Version

```java
/**
 * Returns the submersion ratio (0.0 = fully in air, 1.0 = fully submerged)
 * using a height-scan approach instead of per-block iteration.
 */
private float calculateBuoyancy() {
    if (hullBounds == null || hullData == null) {
        return 0.0f;
    }

    // Refresh water surface cache every WATER_SURFACE_CACHE_TICKS ticks.
    if (waterSurfaceCacheTicks <= 0 || Double.isNaN(cachedWaterSurfaceY)) {
        cachedWaterSurfaceY = findWaterSurfaceY();
        waterSurfaceCacheTicks = WATER_SURFACE_CACHE_TICKS;
    } else {
        waterSurfaceCacheTicks--;
    }

    double hullBottomWorldY = state.position.y + hullBounds.minY();
    double hullHeight       = hullBounds.height();

    if (hullHeight <= 0) return 0.0f;

    double submergedDepth = MathHelper.clamp(
        cachedWaterSurfaceY - hullBottomWorldY, 0.0, hullHeight
    );

    return (float)(submergedDepth / hullHeight);
}

/**
 * Scans upward from just below the hull bottom to find the water surface Y.
 * Returns the Y coordinate of the water surface, or hullBottomWorldY if no water.
 */
private double findWaterSurfaceY() {
    double hullBottomWorldY = state.position.y + (hullBounds != null ? hullBounds.minY() : -0.5);
    int scanX = (int) Math.floor(state.position.x);
    int scanZ = (int) Math.floor(state.position.z);
    int startY = (int) Math.floor(hullBottomWorldY) - 1;
    int maxScan = startY + (hullBounds != null ? (int) Math.ceil(hullBounds.height()) + 4 : 8);

    double lastWaterY = hullBottomWorldY; // default: no water
    for (int y = startY; y <= maxScan; y++) {
        net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(scanX, y, scanZ);
        if (!world.getFluidState(pos).isEmpty()) {
            lastWaterY = y + 1.0; // top of this water block
        } else if (y > startY) {
            break; // first air above water column — we have our surface
        }
    }
    return lastWaterY;
}
```

### 5.6 Replace the `tick()` Buoyancy Section

In the existing `tick()` method, replace the buoyancy force calculation with the density model:

```java
public void tick() {
    float s = calculateBuoyancy(); // submersion ratio [0, 1]

    // --- Gravity ---
    double gravityForce = -PhysicsConfig.GRAVITY * state.mass;

    // --- Density-based buoyancy restoring force ---
    // Target submersion = effectiveRelativeDensity (Archimedes' principle)
    float targetSubmersion = (hullData != null)
        ? MathHelper.clamp(hullData.effectiveRelativeDensity(), 0.01f, 0.99f)
        : 0.5f;

    double displacementError = s - targetSubmersion; // + = too deep, - = too high
    double buoyancyForce     = -displacementError * PhysicsConfig.BUOYANCY_STIFFNESS * state.mass;

    // Only apply buoyancy when in contact with water.
    if (s <= 0.0f) {
        buoyancyForce = 0.0;
    }

    // --- Drag ---
    double dragCoefficient = PhysicsConfig.AIR_DRAG + (s * PhysicsConfig.WATER_DRAG);
    Vec3d dragForce = state.velocity.multiply(-dragCoefficient);

    // --- Sum and integrate ---
    double accelY = (gravityForce + buoyancyForce + dragForce.y) / state.mass;
    double accelX = dragForce.x / state.mass;
    double accelZ = dragForce.z / state.mass;

    state.velocity = new Vec3d(
        state.velocity.x + accelX,
        state.velocity.y + accelY,
        state.velocity.z + accelZ
    );

    state.velocity = clipVelocity(state.velocity);
    state.position = state.position.add(state.velocity);
    state.position = handleTerrainCollision(state.position);

    if (isAtRest()) {
        state.velocity = state.velocity.multiply(PhysicsConfig.SETTLE_DAMPING);
    }
}
```

---

## 6. `PhysicsConfig.java` — Required Constants

File: `src/main/java/net/shasankp000/Ship/Physics/PhysicsConfig.java`

Ensure the following constants exist (add any that are missing):

```java
public static final double BUOYANCY_STIFFNESS = 0.12D;  // spring constant for restoring force
public static final double GRAVITY             = 0.04D;  // blocks/tick² downward
public static final double AIR_DRAG            = 0.01D;
public static final double WATER_DRAG          = 0.05D;
public static final double MAX_VELOCITY        = 2.0D;
public static final double SETTLE_THRESHOLD    = 0.005D;
public static final double SETTLE_DAMPING      = 0.6D;
```

`BUOYANCY_STIFFNESS` controls how aggressively the ship corrects its depth. Start at `0.12` and tune downward if the ship oscillates too much.

---

## 7. `ShipDeployService.java` — Minor Cleanup

File: `src/main/java/net/shasankp000/Ship/ShipDeployService.java`

The current `deployFromCrate()` method does not spawn any `ShipCollisionPartEntity` instances (that was done elsewhere). **No changes required here** beyond verifying there are no lingering references to `ShipCollisionLayerService`. Remove any import or call to it if present.

---

## 8. Cleanup Checklist for Copilot

Before considering the implementation complete, verify all of the following:

- [ ] `ShipCollisionPartEntity.java` is deleted
- [ ] `ShipCollisionLayerService.java` is deleted
- [ ] `ModEntityTypes.java` has no `SHIP_COLLISION_PART_ENTITY` registration
- [ ] No renderer is registered for `ShipCollisionPartEntity` in the client entrypoint
- [ ] `BoatEntityMixin` is created and registered in the mixin JSON config
- [ ] `EntityTickInvoker` accessor mixin is created and registered
- [ ] `ShipBoatEntity.tick()` calls `invokeEntityTick()` (grandparent), NOT `super.tick()`
- [ ] `ShipBoatEntity.recalculateDimensions()` sets `cachedDimensions` to full hull width × height
- [ ] `ShipBoatEntity` no longer contains `applyHullBuoyancy()`, `calculateHullSubmersion()`, or `clampToTerrain()`
- [ ] `ShipPhysicsEngine.calculateBuoyancy()` uses the height-scan approach (Section 5.5)
- [ ] `ShipPhysicsEngine.tick()` uses the density restoring force formula (Section 5.6)
- [ ] `PhysicsConfig` contains `BUOYANCY_STIFFNESS` constant
- [ ] `ShipPhysicsEngine` has `syncFrom()` and `applyTo()` methods wired to `ShipBoatEntity.tick()`
- [ ] The project compiles with no errors referencing deleted classes

---

## 9. Expected Behaviour After Implementation

| Scenario | Expected Result |
|---|---|
| Ship deployed on water | Floats with `ρ_ship` fraction of hull submerged, settles without oscillation |
| Player boards ship | Stands on expanded AABB without falling through the deck |
| `ρ_ship > 1.0` | Ship sinks (gravity > buoyancy, by design) |
| `ρ_ship < 0.3` | Ship sits very high on water, only shallow draft submerged |
| Ship moved onto land | `handleTerrainCollision()` in `ShipPhysicsEngine` pushes it up, no sinking through ground |
| Server with many ships | No `ShipCollisionPartEntity` ticking — server spike issue resolved |
| Vanilla boat spawned | Completely unaffected; `BoatEntityMixin` only cancels for `ShipBoatEntity` instances |

---

## 10. Tuning Notes

- **`BUOYANCY_STIFFNESS`**: If the ship bounces up and down after spawning, reduce this value. If it sinks too slowly, increase it.
- **`effectiveRelativeDensity`**: This is set at ship compile time in `ShipCompileService`. A good default for a wooden sailing ship is `0.45`–`0.60`. Air pockets (empty blocks inside the hull) lower the average density.
- **`SETTLE_DAMPING`** and **`SETTLE_THRESHOLD`**: If the ship creeps horizontally while at rest on water, lower `SETTLE_THRESHOLD` slightly.
- **Water surface scan**: The scan in `findWaterSurfaceY()` only samples the center XZ of the ship. For very wide ships (>10 blocks), consider averaging 4–5 sample points across the hull footprint instead of a single center point.
