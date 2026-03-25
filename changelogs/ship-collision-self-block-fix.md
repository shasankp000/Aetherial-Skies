# Ship Self-Collision Fix — "Inner hitbox lagging behind + teleporting to center"

## Root Cause Analysis

The problem is **the ShipBoatEntity is colliding with its own ShipCollisionPartEntity satellites**. Here's the exact sequence that causes the stutter:

### What happens every tick:

1. Vanilla `BoatEntity.tick()` calls `Entity.move(MovementType.SELF, velocity)`.
2. Inside `Entity.move()`, the engine calls `adjustMovementForCollisions()` which gathers all collidable entities whose AABBs overlap the ship's movement path.
3. Your `ShipCollisionPartEntity.isCollidable()` returns `true` — **this makes them collidable with ALL entities, including their own parent ship**.
4. The ship's expanded AABB (diagonal-sized from `getDimensions()`) overlaps the collision part AABBs that sit on top of the ship.
5. `adjustMovementForCollisions()` calculates that the ship can't move because it would collide with its own collision parts. It clips the movement to zero (or near-zero).
6. The ship's position barely moves (or doesn't move at all).
7. But the renderer and client interpolation already predicted the ship moving at full speed — so visually the ship "jumps ahead" then "snaps back", creating the stuttering lag-behind effect.
8. The `ShipCollisionPartEntity.tick()` then teleports each part to match the ship's (barely moved) position, which creates another position correction next tick.

### Why `collidesWith()` doesn't fully solve it:

Your `ShipCollisionPartEntity.collidesWith()` returns `false` for `ShipBoatEntity`:
```java
public boolean collidesWith(Entity other) {
    if (other instanceof ShipBoatEntity || other instanceof ShipCollisionPartEntity) {
        return false;
    }
    return super.collidesWith(other);
}
```

But this is **only half the equation**. Minecraft's collision system is **bidirectional**. `Entity.move()` on the *ship* side checks `getEntityCollisions()` which asks: "for every entity near me, is that entity collidable?" via `Entity.isCollidable()`. Since `ShipCollisionPartEntity.isCollidable()` returns `true`, the ship sees its own parts as obstacles.

The `collidesWith()` override you have is checked from the *part's* perspective when the *part* moves — not when the *ship* moves. When the ship moves, it's the ship's `Entity.move()` that calls `world.getEntityCollisions(ship, shipAABB)`, which iterates nearby entities and calls `nearbyEntity.isCollidable()` on each one. Your parts return `true`, so they block the ship.

---

## The Fix: Two-pronged approach

### Fix A: Override `collidesWith()` on ShipBoatEntity too

The ship itself needs to declare that it doesn't collide with its own parts:

**In `ShipBoatEntity.java`, add:**
```java
@Override
public boolean collidesWith(Entity other) {
    // Ship must not collide with its own collision parts
    if (other instanceof ShipCollisionPartEntity part) {
        return shipId != null && shipId.equals(part.getOwnerShipId());
        // Returns true only if it's OUR part — meaning we DON'T collide
        // Wait, collidesWith returning true means they DO collide.
        // We need to return FALSE for our own parts.
    }
    return super.collidesWith(other);
}
```

Corrected:
```java
@Override
public boolean collidesWith(Entity other) {
    // Never collide with our own collision layer parts
    if (other instanceof ShipCollisionPartEntity part) {
        // If this part belongs to us, skip collision
        UUID partOwner = part.getOwnerShipId();
        if (partOwner != null && partOwner.equals(this.shipId)) {
            return false;
        }
    }
    return super.collidesWith(other);
}
```

**But this still might not be enough**, because `Entity.move()` → `getEntityCollisions()` uses `VoxelShapes` and calls `isCollidable()` before it ever gets to `collidesWith()`. The flow in vanilla (1.20.1 Fabric/Yarn) is:

```
Entity.move()
  → adjustMovementForCollisions()
    → world.getEntityCollisions(this, box)
      → for each entity in box:
          if entity.isCollidable() → add its AABB as collision shape
```

`collidesWith()` is used for *pushing* logic (`Entity.pushAwayFrom()`), not for the `adjustMovementForCollisions()` path. So we need Fix B.

### Fix B (CRITICAL): Override `isCollidable()` to be context-aware

The problem is `ShipCollisionPartEntity.isCollidable()` unconditionally returns `true`. This tells `getEntityCollisions()` to include it as a collision obstacle for EVERY entity, including its parent ship.

In Minecraft 1.20.1, the entity collision query in `Entity.move()` flows through `World.getEntityCollisions(Entity entity, Box box)`. This method calls `Entity.getCollisionShape()` on each candidate, which checks `isCollidable()`. There is no entity-pair filtering at this stage.

**The correct solution**: Override `isCollidable()` in `ShipCollisionPartEntity` to return `false`, and instead handle collision manually or use a different mechanism.

Wait — if we make `isCollidable()` return `false`, then other entities (players, mobs) won't collide with the ship hull either, defeating the purpose. 

**The real solution: Override `getHardCollisionBox(Entity collidingEntity)` on ShipCollisionPartEntity.**

In Yarn mappings for 1.20.1, the method `Entity.getHardCollisionBox(Entity collidingEntity)` is what `World.getEntityCollisions()` actually uses. This method receives the entity that is trying to move, and returns an AABB (Box) that acts as a collision wall, or `null` for no collision.

```java
// In ShipCollisionPartEntity:

@Override
public Box getHardCollisionBox(Entity collidingEntity) {
    // If the colliding entity is our parent ship, return null (no collision)
    if (collidingEntity instanceof ShipBoatEntity ship) {
        if (this.ownerShipId != null && this.ownerShipId.equals(ship.getShipId())) {
            return null;  // Don't block our own ship
        }
    }
    // If the colliding entity is another collision part of the same ship, no collision
    if (collidingEntity instanceof ShipCollisionPartEntity otherPart) {
        if (this.ownerShipId != null && this.ownerShipId.equals(otherPart.getOwnerShipId())) {
            return null;
        }
    }
    // For all other entities (players, mobs, etc.), return our AABB as collision
    return this.getBoundingBox();
}
```

Also override `getCollisionBox()` to ensure the base collision shape is correct:
```java
@Override
public Box getCollisionBox() {
    return this.getBoundingBox();
}
```

**AND** also override on `ShipBoatEntity` to prevent the ship from being blocked by its own parts:

```java
// In ShipBoatEntity:

@Override
public Box getHardCollisionBox(Entity collidingEntity) {
    // If something is asking about collision with us, use vanilla boat behavior
    // except don't block our own collision parts
    if (collidingEntity instanceof ShipCollisionPartEntity part) {
        if (part.getOwnerShipId() != null && part.getOwnerShipId().equals(this.shipId)) {
            return null;
        }
    }
    return super.getHardCollisionBox(collidingEntity);
}
```

### Fix C: Confirm the Yarn method names

In Yarn 1.20.1 mappings, verify the exact method names. The methods we need are:

- `Entity.getHardCollisionBox(Entity collidingEntity)` — Yarn name: `getHardCollisionBox`
  - Intermediary: `method_30948`
  - Official: might be different
  - This is called by `EntityView.getEntityCollisions()` to build the list of collision shapes
  
- `Entity.isCollidable()` — Yarn name: `isCollidable`
  - This is a simpler check, but `getHardCollisionBox` is the one that provides the actual AABB

If `getHardCollisionBox` doesn't exist in your yarn version, the equivalent might be `getCollisionBox()` (which is the no-arg version that returns the entity's own collision box) paired with `collidesWith(Entity)`. Let me provide the fallback:

### Fallback if `getHardCollisionBox` doesn't exist in your Yarn version:

Check your decompiled `Entity` class for these methods:
```java
// One of these should exist:
public Box getHardCollisionBox(Entity collidingEntity)  // Yarn
public AABB getCollideAgainst(Entity entity)             // Mojmap  
```

If neither exists, the collision filtering happens through `collidesWith()`. In that case, the fix is:

**Override `collidesWith()` on BOTH sides:**

```java
// ShipCollisionPartEntity:
@Override
public boolean collidesWith(Entity other) {
    if (other instanceof ShipBoatEntity ship) {
        return !(ownerShipId != null && ownerShipId.equals(ship.getShipId()));
    }
    if (other instanceof ShipCollisionPartEntity part) {
        return !(ownerShipId != null && ownerShipId.equals(part.getOwnerShipId()));
    }
    return super.collidesWith(other);
}

// ShipBoatEntity:
@Override
public boolean collidesWith(Entity other) {
    if (other instanceof ShipCollisionPartEntity part) {
        return !(shipId != null && shipId.equals(part.getOwnerShipId()));
    }
    return super.collidesWith(other);
}
```

**AND** the key insight — `Entity.move()` in 1.20.1 uses `World.getEntityCollisions(Entity movingEntity, Box expandedBox)`. This method iterates entities in the box and for each one calls:

```java
if (entity.isCollidable() && (movingEntity == null || !movingEntity.isConnectedThroughVehicle(entity))) {
    // entity.getBoundingBox() is used as collision
}
```

Notice: it checks `isCollidable()` but NOT `collidesWith()` for the movement collision path. `collidesWith()` is used for pushing, not for movement blocking.

So the **definitive fix** is to make `isCollidable()` conditional. Since we can't pass context to `isCollidable()`, we have two options:

### Option 1 (Simplest, recommended): Make collision parts NOT collidable, use a mixin for entity-entity collision

Set `isCollidable()` to `false` and instead use a mixin on `Entity.pushAwayFrom()` or on `LivingEntity.travel()` to manually block entities from walking through hull parts. This separates "blocks ship movement" (bad) from "blocks other entities" (good).

### Option 2 (No mixin, practical): Disable expanded AABB for the boat, keep collision parts

Since the collision parts already provide proper collision for external entities, the ship entity itself **doesn't need a huge AABB**. The expanded AABB is what causes the ship to collide with its own parts.

**In `recalculateDimensions()`, keep the AABB at vanilla boat size:**
```java
private void recalculateDimensions() {
    cachedLayerCount = -1;
    cachedSubmersion = 0.0f;
    submersionCheckCooldown = 0;

    ShipHullData hull = getHullData();
    if (hull.blocks().isEmpty()) {
        cachedDimensions = EntityDimensions.changing(1.375f, 0.5625f);
        cachedBounds = null;
        waterlineOffset = 0.0f;
        this.calculateDimensions();
        return;
    }

    ShipHullData.HullBounds bounds = hull.computeBounds();
    cachedBounds = bounds;

    // KEEP THE AABB AT VANILLA BOAT SIZE.
    // The collision parts handle external collision.
    // The buoyancy system uses hull block positions directly, not the AABB.
    // A large AABB causes self-collision with our own parts.
    cachedDimensions = EntityDimensions.changing(1.375f, 0.5625f);
    waterlineOffset = 0.0f;
    this.calculateDimensions();
}
```

**Why this works:**
- The `ShipCollisionPartEntity` satellites already provide the correct collision boundary for other entities (players, mobs).
- The buoyancy calculation (`calculateHullSubmersion()`) already samples water at hull block positions directly — it doesn't use the entity AABB at all.
- The renderer already draws blocks at their `localOffset` positions — it doesn't depend on the AABB.
- The only thing the expanded AABB was supposed to help with was making the ship "feel" bigger for targeting/interaction. But that's what the collision parts are for.

**But wait — what about vanilla `BoatEntity.checkInWater()`?**

Vanilla `checkInWater()` uses the entity's AABB to detect water. With a vanilla-sized AABB, it only checks water right at the center of the ship. This is actually fine because:
1. Your `calculateHullSubmersion()` does the real water check across all hull blocks.
2. Your `applyHullBuoyancy()` adds corrective buoyancy on top of vanilla's.
3. Vanilla's `checkInWater()` and `floatBoat()` will still detect water at the entity center, which is where the ship is deployed (over water).

---

## Recommended Fix (Option 2 + collidesWith on both sides)

This is the minimum-change fix that resolves the issue:

### 1. In `ShipBoatEntity.recalculateDimensions()`:
Keep AABB at vanilla boat size regardless of hull size.

### 2. In `ShipBoatEntity`, add `collidesWith()`:
```java
@Override
public boolean collidesWith(Entity other) {
    if (other instanceof ShipCollisionPartEntity part) {
        UUID partOwner = part.getOwnerShipId();
        if (partOwner != null && partOwner.equals(this.shipId)) {
            return false;
        }
    }
    return super.collidesWith(other);
}
```

### 3. In `ShipCollisionPartEntity.collidesWith()` (already exists, but verify):
```java
@Override
public boolean collidesWith(Entity other) {
    if (other instanceof ShipBoatEntity ship) {
        return !(ownerShipId != null && ownerShipId.equals(ship.getShipId()));
    }
    if (other instanceof ShipCollisionPartEntity otherPart) {
        return !(ownerShipId != null && ownerShipId.equals(otherPart.getOwnerShipId()));
    }
    return super.collidesWith(other);
}
```

### 4. Also add `isCollidable()` guard on the ship side:
The `Entity.move()` path checks `isCollidable()` first. Override on `ShipBoatEntity`:
```java
// This doesn't change ship's own collidability.
// But we should also consider overriding canHit for targeting.
```

Actually, the most reliable fix is to check if `getHardCollisionBox(Entity)` exists in your Yarn version. Run this in your IDE:
- Open `Entity.class` (decompiled)
- Search for `getHardCollisionBox` or `getCollisionBox`
- Look for a method that takes an `Entity` parameter and returns `Box`

If it exists, override it as shown in Fix B above. If not, use Option 2 (keep vanilla AABB + bidirectional `collidesWith`).

---

## Quick Test

After applying the fix, verify:
1. Ship moves smoothly without stuttering
2. Player can still ride the ship
3. Other entities (mobs, players) cannot walk through the ship hull (collision parts still work)
4. Ship still floats correctly (buoyancy uses hull block positions, not AABB)
5. No "Can't keep up" messages

Sources:
- Minecraft client/server desync with boats (https://technical-minecraft.fandom.com/wiki/Client/server_desynchronization)
- Entity collision resolution with large bounding boxes in Forge 1.18.2 (https://forums.minecraftforge.net/topic/111362-version-1182-solved-increased-hitbox-size-only-works-for-entity-collision-but-not-for-block-collision/)
- Modrinth Collision Entity mod approach to custom collidable entities (https://modrinth.com/mod/collision-entity)
