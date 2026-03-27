# Ship Deck Walking — Entity Dragger Pattern Implementation

> **For GitHub Copilot:** This document is the complete specification for fixing active hitbox/mounting bugs and implementing ship-local deck walking. It is a direct continuation of `docs/ship-buoyancy-rework-impl.md`. Read that document first. All paths are relative to project root. Target branch: `1.20.1`.
>
> **Do not use Valkyrien Skies as a dependency.** This document describes a self-contained re-implementation of the same concept, adapted for Aetherial Skies' single-entity ship architecture.
>
> ⚠️ **Implement Phase 1 first. Build and test in-game before starting Phase 2.**

---

## 0. Problem Statement

There are currently **three active bugs** and **one architectural gap** to address:

### Active Bugs (Phase 1)
| # | Bug | Root Cause |
|---|---|---|
| 1 | Player cannot mount boat on right-click | `Entity.baseTick()` does not call `updatePassengerPositions()` — this was never wired up after the vanilla `BoatEntity.tick()` was suppressed |
| 2 | Hitbox is 1 block taller than the ship deck | `recalculateDimensions()` sets AABB height to `cachedBounds.height()` which includes the full block column height, placing the AABB top one block above the visible deck surface |
| 3 | Hitbox does not rotate with the ship | `EntityDimensions`/`Box` is always axis-aligned — cannot be rotated. The expanded AABB is the wrong tool for deck collision entirely |

### Architectural Gap (Phase 2)
Minecraft entity bounding boxes (`Box`/`AABB`) are **always axis-aligned** and fundamentally cannot rotate. The solution is to stop relying on the AABB for deck presence detection entirely, and instead use a server-side tick system that:
- Tracks the ship's position and yaw delta each tick
- Detects players standing on the ship's deck in **ship-local (rotated) space**
- Teleports those players by the ship's motion delta each tick
- Rotates the player's WASD input into the ship's local frame so walking feels natural

---

---
# PHASE 1 — Immediate Bug Fixes
---

> **Copilot: implement only the changes in this section first. Do not touch Phase 2 yet.**
> After Phase 1, the player should be able to mount the ship, stand on it without sinking through visually, and the game should compile and run.

## P1-1. Fix `ShipBoatEntity.tick()` — Restore Passenger Updates (Bug 1)

File: `src/main/java/net/shasankp000/Entity/ShipBoatEntity.java`

`Entity.baseTick()` does not call `updatePassengerPositions()`. This means after mounting, the game never repositions the passenger each tick and the rider is immediately ejected or frozen. Add the call manually.

**Replace the existing `tick()` method with:**

```java
@Override
public void tick() {
    // Run base entity lifecycle (skips BoatEntity vanilla physics via BoatEntityMixin)
    ((EntityTickInvoker) this).invokeBaseTick();

    // Entity.baseTick() does NOT call updatePassengerPositions().
    // Without this, riders are never repositioned and get ejected immediately.
    this.updatePassengerPositions();

    if (!this.getWorld().isClient() && physicsEngine != null) {
        physicsEngine.syncFrom(this);
        physicsEngine.tick();
        physicsEngine.applyTo(this);
    }
}
```

---

## P1-2. Fix `recalculateDimensions()` — Revert AABB to Vanilla Size (Bugs 2 & 3)

File: `src/main/java/net/shasankp000/Entity/ShipBoatEntity.java`

The expanded AABB is the source of both the height overflow (Bug 2) and the rotation mismatch (Bug 3). Since deck walking will be handled by the `ShipDeckDragger` system in Phase 2, the AABB must be reverted to vanilla boat size. This also fixes an unrelated side effect where the large AABB was inflating the fluid volume detected for buoyancy calculations.

**Replace the entire `recalculateDimensions()` method with:**

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

    // Keep AABB at vanilla boat size.
    // Deck presence detection is handled by ShipDeckDragger (Phase 2), not the AABB.
    // An expanded AABB causes:
    //   - the entity to visually stand 1+ blocks above the deck (Bug 2)
    //   - the AABB to be axis-aligned while the hull is rotated (Bug 3)
    //   - buoyancy over-calculation (fluid detection reads AABB volume)
    //   - player mounting to fail in edge cases
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

---

## P1-3. `BoatEntityMixin.java` — No Change Needed

File: `src/main/java/net/shasankp000/mixin/BoatEntityMixin.java`

The existing HEAD-cancel mixin is correct and should remain unchanged. The mount fix in P1-1 (`updatePassengerPositions()`) is sufficient — the mixin itself is not the cause of Bug 1.

---

## P1 Checklist

- [ ] `ShipBoatEntity.tick()` calls `this.updatePassengerPositions()` after `invokeBaseTick()`
- [ ] `ShipBoatEntity.recalculateDimensions()` sets `cachedDimensions = EntityDimensions.changing(1.375f, 0.5625f)` unconditionally (no hull-size expansion)
- [ ] `BoatEntityMixin.java` is left untouched
- [ ] Project compiles with no errors
- [ ] **In-game test:** right-click ship → player mounts at helm offset ✓
- [ ] **In-game test:** player stands on ship → no "standing on air" gap ✓
- [ ] **In-game test:** ship is rotated → player no longer falls through at corners (partial fix; corners fully addressed in Phase 2) ✓

---

---
# PHASE 2 — Deck Walking System (Entity Dragger Pattern)
---

> **Copilot: only begin Phase 2 after Phase 1 has been tested and confirmed working in-game.**

## P2-0. Architecture Overview

```
ShipBoatEntity.tick()
  └── invokeBaseTick() + updatePassengerPositions()   ← from Phase 1
  └── ShipPhysicsEngine.tick()                        ← physics (buoyancy, gravity)
  └── ShipDeckDragger.tickDragging()                  ← NEW: deck player tracking + motion delta
        └── for each nearby ServerPlayerEntity:
              if isStandingOnDeck(player, ship, bounds):  ← rotated footprint check
                  applyShipMotionDelta(player, ship)
                  setDraggingState(player, ship)

MixinLivingEntityMovement (inject into LivingEntity.travel())
  └── if player isDraggedByShip:
        rotate WASD input vector from world space → ship-local space
```

---

## P2-1. New File: `ShipDeckDragger.java`

Create: `src/main/java/net/shasankp000/Ship/Physics/ShipDeckDragger.java`

This is the core of the deck walking system. It runs server-side every tick, finds players standing on the ship's deck in rotated local space, and teleports them by the exact delta the ship moved this tick.

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

public class ShipDeckDragger {

    // How many blocks above the deck top a player can be and still count as on deck.
    private static final double DECK_TOLERANCE_ABOVE = 0.6D;
    // How many blocks below the deck top (feet inside the hull) still count as on deck.
    private static final double DECK_TOLERANCE_BELOW = 0.2D;

    /**
     * Called every server tick from ShipBoatEntity.tick() AFTER the physics engine runs.
     *
     * @param ship       the ship entity
     * @param bounds     pre-computed hull bounds (ShipBoatEntity.cachedBounds) — must not be null
     * @param prevPos    ship position BEFORE physics ran this tick
     * @param prevYaw    ship yaw BEFORE physics ran this tick
     */
    public static void tickDragging(
        ShipBoatEntity ship,
        ShipHullData.HullBounds bounds,
        Vec3d prevPos,
        float prevYaw
    ) {
        if (!(ship.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }
        if (bounds == null) {
            return;
        }

        // Deck Y is the world Y of the top face of the topmost hull block
        double deckWorldY = ship.getY() + bounds.maxY() - 1.0D;
        double halfWidth  = Math.max(bounds.widthX(), bounds.widthZ()) * 0.5D + 0.5D;

        // Delta the ship moved this tick
        Vec3d posDelta = ship.getPos().subtract(prevPos);
        float yawDelta  = MathHelper.wrapDegrees(ship.getYaw() - prevYaw);

        // Search box centred on ship, vertically spanning the deck band
        Box searchBox = new Box(
            ship.getX() - halfWidth,
            deckWorldY - DECK_TOLERANCE_BELOW,
            ship.getZ() - halfWidth,
            ship.getX() + halfWidth,
            deckWorldY + DECK_TOLERANCE_ABOVE + 2.0D,
            ship.getZ() + halfWidth
        );

        List<ServerPlayerEntity> candidates = serverWorld.getEntitiesByClass(
            ServerPlayerEntity.class, searchBox, p -> !p.isSpectator() && !p.hasVehicle()
        );

        for (ServerPlayerEntity player : candidates) {
            if (!isOnDeck(player, ship, bounds, deckWorldY)) {
                // Player has left the deck — clear drag state so movement returns to normal
                if (player instanceof IShipDraggable d) {
                    d.aetherial$setDraggedShipId(null);
                }
                continue;
            }

            // 1. Apply ship's translation delta
            Vec3d newPos = player.getPos().add(posDelta);

            // 2. Apply ship's yaw rotation delta around ship centre
            if (Math.abs(yawDelta) > 0.001f) {
                double dx  = newPos.x - ship.getX();
                double dz  = newPos.z - ship.getZ();
                double rad = Math.toRadians(yawDelta);
                double cos = Math.cos(rad), sin = Math.sin(rad);
                newPos = new Vec3d(
                    ship.getX() + dx * cos - dz * sin,
                    newPos.y,
                    ship.getZ() + dx * sin + dz * cos
                );
            }

            // 3. Snap Y to deck surface only when player is not jumping
            //    (if velocity.y > 0 the player is mid-jump; let them rise freely)
            if (player.getVelocity().y <= 0.0D || player.isOnGround()) {
                newPos = new Vec3d(newPos.x, deckWorldY, newPos.z);
            }

            player.teleport(
                serverWorld,
                newPos.x, newPos.y, newPos.z,
                player.getYaw(), player.getPitch()
            );

            // 4. Tag player as being dragged by this ship
            if (player instanceof IShipDraggable d) {
                d.aetherial$setDraggedShipId(ship.getShipId());
                d.aetherial$setLastShipYaw(ship.getYaw());
            }
        }
    }

    /**
     * Returns true if the player's feet are within the ship's rotated hull footprint
     * at approximately deck height.
     */
    private static boolean isOnDeck(
        ServerPlayerEntity player,
        ShipBoatEntity ship,
        ShipHullData.HullBounds b,
        double deckWorldY
    ) {
        double feetY = player.getY();
        if (feetY < deckWorldY - DECK_TOLERANCE_BELOW
         || feetY > deckWorldY + DECK_TOLERANCE_ABOVE) {
            return false;
        }

        // Rotate player position into ship-local space
        double dx     = player.getX() - ship.getX();
        double dz     = player.getZ() - ship.getZ();
        double yawRad = Math.toRadians(-ship.getYaw());
        double cos    = Math.cos(yawRad), sin = Math.sin(yawRad);
        double localX = dx * cos + dz * sin;
        double localZ = -dx * sin + dz * cos;

        double margin = 0.3D;
        return localX >= b.minX() - margin && localX <= b.maxX() + margin
            && localZ >= b.minZ() - margin && localZ <= b.maxZ() + margin;
    }
}
```

---

## P2-2. New File: `IShipDraggable.java` — Mixin Duck Interface

Create: `src/main/java/net/shasankp000/mixin/IShipDraggable.java`

This is a **plain Java interface** — do NOT register it as a mixin. It is implemented by `MixinServerPlayerDragging` which IS registered.

```java
package net.shasankp000.mixin;

import java.util.UUID;

/**
 * Duck interface implemented by MixinServerPlayerDragging on ServerPlayerEntity.
 * Carries per-player ship-dragging state used by ShipDeckDragger.
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

## P2-3. New File: `MixinServerPlayerDragging.java`

Create: `src/main/java/net/shasankp000/mixin/MixinServerPlayerDragging.java`

Injects the dragging state fields onto `ServerPlayerEntity` via Mixin.

```java
package net.shasankp000.mixin;

import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.UUID;

@Mixin(ServerPlayerEntity.class)
public class MixinServerPlayerDragging implements IShipDraggable {

    @Unique private UUID   aetherial$draggedShipId = null;
    @Unique private float  aetherial$lastShipYaw   = 0.0f;

    @Override public UUID  aetherial$getDraggedShipId()       { return aetherial$draggedShipId; }
    @Override public void  aetherial$setDraggedShipId(UUID id){ this.aetherial$draggedShipId = id; }
    @Override public float aetherial$getLastShipYaw()         { return aetherial$lastShipYaw; }
    @Override public void  aetherial$setLastShipYaw(float y)  { this.aetherial$lastShipYaw = y; }
    @Override public boolean aetherial$isDraggedByShip()      { return aetherial$draggedShipId != null; }
}
```

---

## P2-4. New File: `MixinLivingEntityMovement.java` — Rotate Input Vector

Create: `src/main/java/net/shasankp000/mixin/MixinLivingEntityMovement.java`

Intercepts `LivingEntity.travel(Vec3d)` and rotates the player's WASD input into ship-local space while they are being dragged. Without this, pressing W moves the player toward world north even if the ship is facing east.

```java
package net.shasankp000.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LivingEntity.class)
public class MixinLivingEntityMovement {

    /**
     * Rotates the movement input vector from world-facing space to ship-local space
     * when the player is standing on a ship's deck.
     *
     * Yarn 1.20.1: LivingEntity.travel(Vec3d movementInput)
     * index = 1 refers to the sole Vec3d parameter.
     * If the build fails on this annotation, change index to 0 or use argsOnly = true.
     */
    @ModifyVariable(
        method = "travel",
        at = @At("HEAD"),
        argsOnly = true,
        index = 1
    )
    private Vec3d aetherial$rotateInputToShipLocal(Vec3d movementInput) {
        if (!((Object) this instanceof ServerPlayerEntity player)) {
            return movementInput;
        }
        if (!(player instanceof IShipDraggable d) || !d.aetherial$isDraggedByShip()) {
            return movementInput;
        }

        // relativeYaw = how much the player is facing away from the ship's bow
        double rad = Math.toRadians(player.getYaw() - d.aetherial$getLastShipYaw());
        double cos = Math.cos(rad), sin = Math.sin(rad);

        // movementInput: x = strafe, y = vertical (jump/sneak), z = forward
        return new Vec3d(
            movementInput.x * cos - movementInput.z * sin,
            movementInput.y,
            movementInput.x * sin + movementInput.z * cos
        );
    }
}
```

---

## P2-5. Update `ShipBoatEntity.tick()` — Wire the Dragger

File: `src/main/java/net/shasankp000/Entity/ShipBoatEntity.java`

Capture `prevPos` and `prevYaw` **before** physics runs so the dragger can compute the delta. Pass `cachedBounds` directly to avoid recomputing bounds every tick per player.

Add import at top of file:
```java
import net.shasankp000.Ship.Physics.ShipDeckDragger;
```

**Replace the `tick()` method produced in Phase 1 with:**

```java
@Override
public void tick() {
    // Snapshot pre-physics position and yaw for delta calculation in ShipDeckDragger
    Vec3d prevPos = this.getPos();
    float prevYaw = this.getYaw();

    ((EntityTickInvoker) this).invokeBaseTick();
    this.updatePassengerPositions();

    if (!this.getWorld().isClient()) {
        if (physicsEngine != null) {
            physicsEngine.syncFrom(this);
            physicsEngine.tick();
            physicsEngine.applyTo(this);
        }

        // Run AFTER physics so posDelta = newPos - oldPos is the actual movement this tick
        if (cachedBounds != null) {
            ShipDeckDragger.tickDragging(this, cachedBounds, prevPos, prevYaw);
        }
    }
}
```

---

## P2-6. Register New Mixins

File: `src/main/resources/aetherial-skies.mixins.json`

Add to the `"mixins"` array:

```json
"MixinServerPlayerDragging",
"MixinLivingEntityMovement"
```

Full `"mixins"` array after change:
```json
"mixins": [
  "BoatEntityMixin",
  "EntityTickInvoker",
  "MixinServerPlayerDragging",
  "MixinLivingEntityMovement"
]
```

> `IShipDraggable` is a plain interface — do **not** add it here.

---

## P2 Checklist

- [ ] `ShipDeckDragger.java` created
- [ ] `IShipDraggable.java` created (plain interface, NOT in mixins.json)
- [ ] `MixinServerPlayerDragging.java` created
- [ ] `MixinLivingEntityMovement.java` created
- [ ] `ShipBoatEntity.tick()` updated with `prevPos`/`prevYaw` capture and `ShipDeckDragger.tickDragging()` call
- [ ] New mixins registered in `aetherial-skies.mixins.json`
- [ ] Project compiles with no errors
- [ ] **In-game test:** player dismounts and walks on deck → moves with ship, not in world space ✓
- [ ] **In-game test:** ship rotates while player on deck → player rotates with it, no drift ✓
- [ ] **In-game test:** player reaches hull edge → falls off naturally ✓
- [ ] **In-game test:** player jumps on deck → jump arc is not interrupted ✓

---

## P2-7. Tuning Notes

- **`DECK_TOLERANCE_ABOVE` (0.6):** Increase if players are not picked up when standing on deck. Decrease if flying players near the ship get dragged unexpectedly.
- **`DECK_TOLERANCE_BELOW` (0.2):** Increase if players sink slightly below the deck surface before being snapped up.
- **`margin` (0.3) in `isOnDeck()`:** The extra outward margin on the hull footprint rectangle. Increase if players fall off at corners; decrease if they are dragged when clearly off the ship.
- **`teleport()` vs `setPosition()`:** The dragger uses `player.teleport()` to force-sync position to the client each tick. If this causes rubber-banding, switch to `player.setPosition()` followed by `player.networkHandler.syncWithPlayerPosition()`.
- **Jump guard:** The Y-snap is already guarded by `player.getVelocity().y <= 0 || player.isOnGround()`. If players still cannot jump, widen this to also check `player.isHoldingOntoLadder()`.

---

## P2-8. Expected Behaviour After Full Implementation

| Scenario | Expected Result |
|---|---|
| Right-click ship | Mounts at helm offset — `updatePassengerPositions()` called each tick |
| Player on deck, ship drifts | Player moves with ship — `ShipDeckDragger` applies translation delta |
| Ship turns while player on deck | Player rotates with ship around ship centre — no sliding off |
| Player walks forward on deck | `MixinLivingEntityMovement` rotates W input to ship-local forward |
| Player reaches hull edge | `isOnDeck()` returns false, dragging stops, player falls naturally |
| Player jumps on deck | Jump arc not interrupted — Y-snap skipped while `velocity.y > 0` |
| Vanilla boat spawned | No effect — dragger only runs from `ShipBoatEntity.tick()` |
