# Ship Deck Walking — Entity Dragger Pattern Implementation

> **For GitHub Copilot:** This document is the complete specification for implementing ship-local player movement and deck walking. It is a direct continuation of `docs/ship-buoyancy-rework-impl.md`. Read that document first. All paths are relative to project root. Target branch: `1.20.1`.
>
> **Do not use Valkyrien Skies as a dependency.** This document describes a self-contained re-implementation of the same concept, adapted for Aetherial Skies' single-entity ship architecture.

---

## 0. Problem Statement

Minecraft entity bounding boxes (`Box`/`AABB`) are always **axis-aligned**. They cannot rotate with the ship. This causes two problems:

1. **Corner fall-through:** When the ship is rotated, the axis-aligned box does not cover the rotated corners of the visual hull. The player steps off the box and falls.
2. **Deck walking misalignment:** When the player walks on deck while the ship is moving or turning, their movement is in world space, not ship-local space. They drift and slide relative to the ship frame.

**The solution (inspired by VS2's EntityDragger pattern) does not fight the AABB limitation — it works around it entirely:**

- Keep the AABB at vanilla size (do NOT expand it to hull dimensions).
- Track the ship's position and yaw delta each tick.
- Detect players standing on the ship's deck surface each tick.
- Apply the ship's motion delta directly to those players.
- Rotate the player's input vector into the ship's local frame so movement feels natural.

The player never needs to stand on the AABB. They stand on the ship deck visually, and the dragger system keeps them stuck to it programmatically.

---

## 1. Architecture Overview

```
ShipBoatEntity.tick()
  └── ShipPhysicsEngine.tick()         ← physics (buoyancy, gravity)
  └── ShipDeckDragger.tickDragging()   ← NEW: deck player tracking + motion delta
        └── for each nearby ServerPlayerEntity:
              if isStandingOnDeck(player, ship):
                  applyShipMotionDelta(player, ship)
                  setDraggingState(player, ship)

LivingEntityMovementMixin (inject into LivingEntity.travel())
  └── if player isDraggedByShip:
        rotate input vector from world space → ship-local space
        call original travel() with rotated input
        rotate output velocity back → world space
```

---

## 2. New Files to Create

### 2.1 `ShipDeckDragger.java`

Create: `src/main/java/net/shasankp000/Ship/Physics/ShipDeckDragger.java`

This class runs server-side each tick inside `ShipBoatEntity`. It:
- Scans nearby players within the ship's footprint
- Detects which are standing on the deck surface
- Applies the ship's motion delta to those players
- Tags them with a `DraggingInfo` stored in a companion interface

```java
package net.shasankp000.Ship.Physics;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Entity.ShipBoatEntity;
import net.shasankp000.Ship.ShipHullData;
import net.shasankp000.mixin.IShipDraggable;

import java.util.List;
import java.util.UUID;

public class ShipDeckDragger {

    // How many blocks above the top of the ship AABB a player can be and still count as "on deck".
    private static final double DECK_TOLERANCE_ABOVE = 0.6D;
    // How many blocks below the ship top surface counts as "inside the deck" (feet in the hull).
    private static final double DECK_TOLERANCE_BELOW = 0.2D;

    /**
     * Called every server tick from ShipBoatEntity.tick().
     *
     * @param ship        the ship entity
     * @param prevPos     ship position at the START of this tick (before physics engine ran)
     * @param prevYaw     ship yaw at the START of this tick
     */
    public static void tickDragging(ShipBoatEntity ship, Vec3d prevPos, float prevYaw) {
        if (!(ship.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        ShipHullData hull = ship.getHullData();
        if (hull == null || hull.blocks().isEmpty()) {
            return;
        }

        ShipHullData.HullBounds bounds = hull.computeBounds();
        // Deck Y is the world Y of the top surface of the hull (where the player stands)
        double deckWorldY = ship.getY() + bounds.maxY() - 1.0D;
        double halfWidth  = Math.max(bounds.widthX(), bounds.widthZ()) * 0.5D + 0.5D; // small margin

        // Position delta applied by physics engine this tick
        Vec3d posDelta = ship.getPos().subtract(prevPos);
        // Yaw delta this tick
        float yawDelta = MathHelper.wrapDegrees(ship.getYaw() - prevYaw);

        // Search box: generous horizontal area around ship, only near deck height
        Box searchBox = new Box(
            ship.getX() - halfWidth,
            deckWorldY - DECK_TOLERANCE_BELOW,
            ship.getZ() - halfWidth,
            ship.getX() + halfWidth,
            deckWorldY + DECK_TOLERANCE_ABOVE + 2.0D,
            ship.getZ() + halfWidth
        );

        List<ServerPlayerEntity> candidates = serverWorld.getEntitiesByClass(
            ServerPlayerEntity.class, searchBox, p -> !p.isSpectator()
        );

        for (ServerPlayerEntity player : candidates) {
            if (!isOnDeck(player, ship, deckWorldY, halfWidth)) {
                // Player left the deck — clear dragging state
                if (player instanceof IShipDraggable draggable) {
                    draggable.aetherial$setDraggedShipId(null);
                }
                continue;
            }

            // Apply ship translation delta
            Vec3d newPlayerPos = player.getPos().add(posDelta);

            // Apply ship yaw rotation delta around ship center
            if (Math.abs(yawDelta) > 0.001f) {
                double dx = newPlayerPos.x - ship.getX();
                double dz = newPlayerPos.z - ship.getZ();
                double angle = Math.toRadians(yawDelta);
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);
                double rotX = dx * cos - dz * sin;
                double rotZ = dx * sin + dz * cos;
                newPlayerPos = new Vec3d(
                    ship.getX() + rotX,
                    newPlayerPos.y,
                    ship.getZ() + rotZ
                );
            }

            // Snap player Y to deck surface to prevent sinking through
            // Player feet should be exactly at deckWorldY
            newPlayerPos = new Vec3d(newPlayerPos.x, deckWorldY, newPlayerPos.z);

            player.teleport(
                serverWorld,
                newPlayerPos.x,
                newPlayerPos.y,
                newPlayerPos.z,
                player.getYaw(),
                player.getPitch()
            );

            // Mark this player as being dragged by this ship
            if (player instanceof IShipDraggable draggable) {
                draggable.aetherial$setDraggedShipId(ship.getShipId());
                draggable.aetherial$setLastShipYaw(ship.getYaw());
            }
        }
    }

    /**
     * Returns true if the player is standing on the ship's deck surface within the
     * rotated hull footprint.
     */
    private static boolean isOnDeck(
        ServerPlayerEntity player,
        ShipBoatEntity ship,
        double deckWorldY,
        double halfWidth
    ) {
        double playerFeetY = player.getY();

        // Player feet must be near the deck surface
        if (playerFeetY < deckWorldY - DECK_TOLERANCE_BELOW
         || playerFeetY > deckWorldY + DECK_TOLERANCE_ABOVE) {
            return false;
        }

        // Transform player world position into ship-local space and check hull footprint
        double dx = player.getX() - ship.getX();
        double dz = player.getZ() - ship.getZ();
        double yawRad = Math.toRadians(-ship.getYaw());
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        double localX = dx * cos + dz * sin;
        double localZ = -dx * sin + dz * cos;

        ShipHullData hull = ship.getHullData();
        if (hull == null) return false;
        ShipHullData.HullBounds b = hull.computeBounds();

        // Small inward margin so player standing at edge still counts
        double margin = 0.3D;
        return localX >= b.minX() - margin && localX <= b.maxX() + margin
            && localZ >= b.minZ() - margin && localZ <= b.maxZ() + margin;
    }
}
```

---

### 2.2 `IShipDraggable.java` — Mixin Duck Interface

Create: `src/main/java/net/shasankp000/mixin/IShipDraggable.java`

This interface is injected onto `ServerPlayerEntity` via a mixin to carry per-player dragging state without polluting the player class. This is exactly the "mixin duck" pattern used by VS2.

```java
package net.shasankp000.mixin;

import java.util.UUID;

/**
 * Injected onto ServerPlayerEntity via MixinServerPlayerDragging.
 * Carries the ship-dragging state for deck walking.
 */
public interface IShipDraggable {
    UUID aetherial$getDraggedShipId();
    void aetherial$setDraggedShipId(UUID shipId);
    float aetherial$getLastShipYaw();
    void aetherial$setLastShipYaw(float yaw);
    boolean aetherial$isDraggedByShip();
}
```

---

### 2.3 `MixinServerPlayerDragging.java` — Implements the Duck Interface

Create: `src/main/java/net/shasankp000/mixin/MixinServerPlayerDragging.java`

This mixin injects the dragging state fields onto `ServerPlayerEntity`.

```java
package net.shasankp000.mixin;

import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.UUID;

@Mixin(ServerPlayerEntity.class)
public class MixinServerPlayerDragging implements IShipDraggable {

    @Unique
    private UUID aetherial$draggedShipId = null;

    @Unique
    private float aetherial$lastShipYaw = 0.0f;

    @Override
    public UUID aetherial$getDraggedShipId() {
        return aetherial$draggedShipId;
    }

    @Override
    public void aetherial$setDraggedShipId(UUID shipId) {
        this.aetherial$draggedShipId = shipId;
    }

    @Override
    public float aetherial$getLastShipYaw() {
        return aetherial$lastShipYaw;
    }

    @Override
    public void aetherial$setLastShipYaw(float yaw) {
        this.aetherial$lastShipYaw = yaw;
    }

    @Override
    public boolean aetherial$isDraggedByShip() {
        return aetherial$draggedShipId != null;
    }
}
```

---

### 2.4 `MixinLivingEntityMovement.java` — Rotate Input Vector

Create: `src/main/java/net/shasankp000/mixin/MixinLivingEntityMovement.java`

This is the most important mixin. It intercepts player movement input **before** Minecraft's physics integrates it, rotates it into ship-local space, then rotates the resulting velocity back. The player feels like they are walking relative to the ship's heading rather than world north.

```java
package net.shasankp000.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class MixinLivingEntityMovement {

    /**
     * Intercepts the movementInput Vec3d passed to LivingEntity.travel().
     * If the player is being dragged by a ship, rotates the input vector
     * from world space into ship-local space.
     *
     * The ship's yaw is stored in IShipDraggable.aetherial$getLastShipYaw().
     */
    @ModifyVariable(
        method = "travel",
        at = @At("HEAD"),
        argsOnly = true,
        index = 1
    )
    private Vec3d aetherial$rotateInputToShipLocal(Vec3d movementInput) {
        LivingEntity self = (LivingEntity)(Object)this;

        // Only apply to server-side players being dragged by a ship
        if (!(self instanceof ServerPlayerEntity player)) {
            return movementInput;
        }
        if (!(player instanceof IShipDraggable draggable)) {
            return movementInput;
        }
        if (!draggable.aetherial$isDraggedByShip()) {
            return movementInput;
        }

        float shipYaw = draggable.aetherial$getLastShipYaw();
        // Player's own facing yaw
        float playerYaw = player.getYaw();
        // Relative yaw: how far the player is rotated from the ship's heading
        float relativeYaw = playerYaw - shipYaw;

        // Rotate the STRAFE (x) and FORWARD (z) components of movement input
        // from the player's world-facing direction into the ship-local frame.
        // movementInput: x = strafe, y = vertical, z = forward
        double rad = Math.toRadians(relativeYaw);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        double localX = movementInput.x * cos - movementInput.z * sin;
        double localZ = movementInput.x * sin + movementInput.z * cos;

        return new Vec3d(localX, movementInput.y, localZ);
    }
}
```

**Important note for Copilot:** The `@ModifyVariable` index may need adjustment depending on the exact Yarn mapping of `LivingEntity.travel(Vec3d)` in 1.20.1. Verify the method signature is `travel(Vec3d movementInput)` and that index 1 correctly refers to the `movementInput` parameter. If not, adjust the index or use `ordinal = 0` instead.

---

## 3. Modifications to Existing Files

### 3.1 `ShipBoatEntity.java` — Wire the Dragger

The dragger needs the ship's position and yaw **before** the physics engine moves it. Capture them at the start of `tick()` and pass to the dragger after physics.

```java
@Override
public void tick() {
    // Capture pre-physics state for the dragger delta calculation
    Vec3d prevPos = this.getPos();
    float prevYaw = this.getYaw();

    // Run base entity lifecycle (skips BoatEntity vanilla physics via BoatEntityMixin)
    ((EntityTickInvoker) this).invokeBaseTick();
    this.updatePassengerPositions();

    if (!this.getWorld().isClient()) {
        // 1. Run physics engine (modifies position + velocity)
        if (physicsEngine != null) {
            physicsEngine.syncFrom(this);
            physicsEngine.tick();
            physicsEngine.applyTo(this);
        }

        // 2. Run deck dragger AFTER physics so delta is position_new - position_old
        ShipDeckDragger.tickDragging(this, prevPos, prevYaw);
    }
}
```

Also add this import at the top of `ShipBoatEntity.java`:
```java
import net.shasankp000.Ship.Physics.ShipDeckDragger;
```

### 3.2 `ShipBoatEntity.recalculateDimensions()` — Revert to Vanilla AABB Size

With the dragger system handling deck walking, the expanded AABB is no longer needed and actively causes problems (sinking, self-collision). Revert to vanilla boat dimensions:

```java
private void recalculateDimensions() {
    cachedLayerCount = -1;

    ShipHullData hull = getHullData();
    if (hull == null || hull.blocks().isEmpty()) {
        cachedDimensions = EntityDimensions.changing(1.375f, 0.5625f);
        cachedBounds = null;
        physicsEngine = null;
        this.calculateDimensions();
        return;
    }

    cachedBounds = hull.computeBounds();

    // IMPORTANT: Keep AABB at vanilla boat size.
    // Deck walking is handled by ShipDeckDragger, not the AABB.
    // An expanded AABB causes the entity to fight its own buoyancy
    // (fluid detection reads the AABB volume) and blocks player mounting.
    cachedDimensions = EntityDimensions.changing(1.375f, 0.5625f);

    ShipPhysicsState newState = new ShipPhysicsState();
    newState.position = this.getPos();
    newState.velocity = this.getVelocity();
    newState.yaw = this.getYaw();
    newState.mass = Math.max(1.0f, hull.mass());

    physicsEngine = new ShipPhysicsEngine(newState, this.getWorld(), hull.totalDisplacedVolume());
    physicsEngine.setHullBounds(cachedBounds);
    physicsEngine.setHullData(hull);

    this.calculateDimensions();
}
```

### 3.3 `ShipBoatEntity.updatePassengerPosition()` — Fix Mounting

The current implementation correctly positions the rider at the helm offset. However, we must also fix the issue where the player cannot mount via right-click. The root issue is that `BoatEntityMixin` was cancelling `BoatEntity.tick()` at `HEAD`, which prevented the internal `inWater`/`onGround` flags from being set — flags that `BoatEntity.interact()` checks before allowing a rider.

Change `BoatEntityMixin` to cancel at the movement/physics step, not at `HEAD` (see Section 4 below). This allows `interact()` and passenger setup to run normally.

---

## 4. Fix `BoatEntityMixin.java` — Mount Interaction Restored

File: `src/main/java/net/shasankp000/mixin/BoatEntityMixin.java`

**Replace the existing mixin entirely:**

```java
package net.shasankp000.mixin;

import net.minecraft.entity.vehicle.BoatEntity;
import net.shasankp000.Entity.ShipBoatEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BoatEntity.class)
public class BoatEntityMixin {

    /**
     * For ShipBoatEntity, we suppress vanilla BoatEntity.tick() entirely at HEAD.
     * ShipBoatEntity.tick() calls invokeBaseTick() (Entity.baseTick) directly,
     * then ShipPhysicsEngine, then ShipDeckDragger.
     *
     * The mounting/interaction issue is NOT caused by this mixin — it is caused by
     * Entity.baseTick() not calling updatePassengerPositions(). ShipBoatEntity.tick()
     * now calls this.updatePassengerPositions() manually after invokeBaseTick().
     * See Section 3.1.
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void aetherialSkies$suppressVanillaBoatTickForShip(CallbackInfo ci) {
        if ((Object) this instanceof ShipBoatEntity) {
            ci.cancel();
        }
    }
}
```

---

## 5. Register New Mixins

In `src/main/resources/aetherial-skies.mixins.json`, add the following to the `"mixins"` array:

```json
"MixinServerPlayerDragging",
"MixinLivingEntityMovement"
```

The full `"mixins"` array should now include:
```json
"mixins": [
  "BoatEntityMixin",
  "EntityTickInvoker",
  "MixinServerPlayerDragging",
  "MixinLivingEntityMovement"
]
```

---

## 6. `ShipHullData.java` — Cache `computeBounds()`

`ShipDeckDragger.isOnDeck()` calls `hull.computeBounds()` every tick for every nearby player. `computeBounds()` iterates all blocks every call. To prevent this from becoming a new performance problem, **add a cached bounds field to `ShipBoatEntity`** (already exists as `cachedBounds`) and pass it into the dragger instead of recomputing.

Change `ShipDeckDragger.tickDragging()` signature to accept pre-computed bounds:

```java
public static void tickDragging(
    ShipBoatEntity ship,
    ShipHullData.HullBounds bounds,   // pass cachedBounds from ShipBoatEntity
    Vec3d prevPos,
    float prevYaw
)
```

And in `ShipBoatEntity.tick()`:
```java
ShipDeckDragger.tickDragging(this, cachedBounds, prevPos, prevYaw);
```

Update `isOnDeck()` to accept `HullBounds` as a parameter instead of calling `hull.computeBounds()` internally.

---

## 7. `ShipPhysicsEngine.java` — Expose Prev State for Delta

The dragger needs the ship's position BEFORE physics runs. This is already handled in `ShipBoatEntity.tick()` by capturing `prevPos = this.getPos()` before calling `physicsEngine.tick()`. No changes needed to `ShipPhysicsEngine`.

---

## 8. Implementation Checklist for Copilot

- [ ] `ShipDeckDragger.java` created at `src/main/java/net/shasankp000/Ship/Physics/ShipDeckDragger.java`
- [ ] `IShipDraggable.java` interface created at `src/main/java/net/shasankp000/mixin/IShipDraggable.java`
- [ ] `MixinServerPlayerDragging.java` created at `src/main/java/net/shasankp000/mixin/MixinServerPlayerDragging.java`
- [ ] `MixinLivingEntityMovement.java` created at `src/main/java/net/shasankp000/mixin/MixinLivingEntityMovement.java`
- [ ] `BoatEntityMixin.java` updated per Section 4
- [ ] `ShipBoatEntity.tick()` captures `prevPos` and `prevYaw` before physics, calls `ShipDeckDragger.tickDragging()` after physics
- [ ] `ShipBoatEntity.tick()` calls `this.updatePassengerPositions()` after `invokeBaseTick()`
- [ ] `ShipBoatEntity.recalculateDimensions()` reverts AABB to vanilla size `(1.375f, 0.5625f)`
- [ ] `ShipDeckDragger.tickDragging()` accepts `HullBounds` from `ShipBoatEntity.cachedBounds`
- [ ] `IShipDraggable` is NOT registered as a mixin — it is a plain Java interface. Only the implementation `MixinServerPlayerDragging` is registered.
- [ ] New mixins registered in `aetherial-skies.mixins.json`
- [ ] Project compiles with no errors

---

## 9. Expected Behaviour After Implementation

| Scenario | Expected Result |
|---|---|
| Player right-clicks ship | Mounts successfully at helm offset — `updatePassengerPositions()` is called each tick |
| Player dismounts, walks on deck | `ShipDeckDragger` detects them within the rotated footprint, teleports them with ship each tick |
| Ship turns while player is on deck | Player rotates with ship around ship center — no sliding off |
| Player walks forward on deck | `MixinLivingEntityMovement` rotates input vector to ship-local frame — player moves relative to ship heading |
| Player reaches hull edge | `isOnDeck()` returns false, dragging stops, player falls naturally |
| Player jumps on deck | During jump arc, `playerFeetY` moves above `deckWorldY + DECK_TOLERANCE_ABOVE` — dragging temporarily pauses, resumes on landing |
| Vanilla boat spawned | No effect — `IShipDraggable` check only runs if player is already tagged, `ShipDeckDragger` only runs from `ShipBoatEntity.tick()` |

---

## 10. Tuning Notes

- **`DECK_TOLERANCE_ABOVE` (0.6):** If players are not picked up when standing on deck, increase this slightly. If flying players get dragged, decrease it.
- **`DECK_TOLERANCE_BELOW` (0.2):** Controls how deep inside the hull a player can be and still get dragged upward. Increase if players sink through slightly.
- **`margin` (0.3) in `isOnDeck()`:** The hull footprint margin. Increase if players fall off at corners; decrease if players are dragged when clearly off the ship.
- **`teleport()` vs `setPosition()`:** The implementation uses `player.teleport()` to force-sync position to the client each tick. If this causes rubber-banding or jitter, switch to `player.setPosition()` + `player.networkHandler.syncWithPlayerPosition()` instead.
- **Jump handling:** If the Y-snap (`newPlayerPos = new Vec3d(..., deckWorldY, ...)`) prevents the player from jumping, add a guard: only snap Y if `player.isOnGround()` or `player.getVelocity().y <= 0`.
