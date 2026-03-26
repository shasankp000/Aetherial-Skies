# Final Performance Fix — Server Spikes + Ship Speed

## The Real Source of the 32-Second Freeze

The remaining server spikes are NOT from your code's explicit `getEntitiesByClass()` calls — those have been fixed. The spikes are caused by **vanilla's entity collision system processing your `ShipCollisionPartEntity` entities every tick**.

Here's why: `ShipCollisionPartEntity.isCollidable()` returns `true`. This means:

1. **Every entity near the ship** (mobs, items, XP orbs, falling blocks, the player, the ship itself) that calls `Entity.move()` will scan for collidable entities in its path. Each one finds your collision parts and runs `adjustMovementForCollisions()` against them.

2. **The collision parts themselves** call `super.tick()` which runs `Entity.tick()` → `Entity.baseTick()`. The base `Entity.tick()` in vanilla calls `checkBlockCollision()`, water state checks, fire checks, and processes velocity. Even though you set `noClip = false` and `noGravity = true`, the base tick still does a lot of work. And importantly, `Entity.tick()` processes entity pushing via `pushAwayFrom()` — each collision part checks for other entities to push.

3. **The compounding effect**: For a single-layer ship with 1 collision part, the cost is modest. But if the server is already slightly behind (the first 2-second spike), Minecraft tries to catch up by processing more ticks in a burst. Each catch-up tick processes all the collision parts, which takes slightly longer than 50ms, causing the server to fall further behind. This is a **death spiral** — the 2-second delay causes catch-up ticks, which cause more delay, snowballing to 32 seconds.

## The Fix: Three Changes

### 1. Disable `super.tick()` in ShipCollisionPartEntity

The collision parts don't need ANY of vanilla's `Entity.tick()` processing. They don't move under their own physics, don't check for fire, don't need water state, don't push other entities. They just teleport to follow the ship. Replace `super.tick()` with `this.baseTick()` — wait, actually even `baseTick()` does a lot. The cleanest approach:

```java
@Override
public void tick() {
    // SKIP super.tick() entirely — these parts are manually positioned.
    // Only increment age for discarding logic.
    this.age++;
    
    if (this.getWorld().isClient()) {
        return;
    }

    if (ownerShipId == null) {
        this.discard();
        return;
    }

    ShipBoatEntity ship = resolveOwner();
    if (ship == null) {
        missingOwnerTicks++;
        if (missingOwnerTicks > 120) {
            this.discard();
        }
        return;
    }
    missingOwnerTicks = 0;

    double yawRad = Math.toRadians(-ship.getYaw());
    double cos = Math.cos(yawRad);
    double sin = Math.sin(yawRad);
    double rotatedX = localOffset.x * cos - localOffset.z * sin;
    double rotatedZ = localOffset.x * sin + localOffset.z * cos;

    double centerX = ship.getX() + rotatedX;
    double centerY = ship.getY() + localOffset.y;
    double centerZ = ship.getZ() + rotatedZ;

    this.setPosition(centerX, centerY, centerZ);
    this.setYaw(ship.getYaw());
    this.setPitch(ship.getPitch());
    this.setBoundingBox(new Box(
            centerX - halfSize.x,
            centerY - halfSize.y,
            centerZ - halfSize.z,
            centerX + halfSize.x,
            centerY + halfSize.y,
            centerZ + halfSize.z
    ));
}
```

**Why**: `Entity.tick()` calls `baseTick()` which does:
- Fire/lava damage checks
- Water state updates (`updateWaterState()`)
- Block collision checks (`checkBlockCollision()`)
- Portal detection
- Velocity application and movement (`move()`)
- Entity pushing (`pushAwayFrom` calls)

None of these are needed for a part that's just a collision shape teleported to follow a ship. Skipping `super.tick()` removes all this overhead.

### 2. Override `isPushable()` AND `pushAwayFrom()` on collision parts

Already have `isPushable() → false`, but also add:

```java
@Override
public void pushAwayFrom(Entity entity) {
    // Do nothing — collision parts never push entities
}

@Override
public void pushAway(Entity entity) {
    // Do nothing
}
```

### 3. Also set `noClip = true` on collision parts

Since the collision parts don't need to interact with blocks at all (they're just collision shapes for other entities), set `noClip = true`. This prevents vanilla from running `checkBlockCollision()` and movement collision resolution on the parts:

```java
public ShipCollisionPartEntity(EntityType<? extends ShipCollisionPartEntity> type, World world) {
    super(type, world);
    this.noClip = true;    // was false — parts don't need block collision
    this.setNoGravity(true);
}
```

Wait — if `noClip = true`, does that affect `isCollidable()`? Let me check... No, `noClip` only affects the entity's own `move()` calls (it skips collision resolution when the entity moves). Other entities checking collision against this entity still use `isCollidable()` and `getBoundingBox()`. So `noClip = true` is safe here — it just means vanilla won't try to move the collision part out of blocks, which we don't want anyway.

## About Ship Speed (Not Sprinting)

Vanilla boats don't support sprinting — there's no sprint mechanic in `BoatEntity.controlBoat()`. The boat has a fixed speed from paddle input. If it feels slow compared to a vanilla boat, it might be because:

1. The vertical damping `updated.y * 0.92D` in `applyHullBuoyancy()` might be slightly fighting vanilla's own vertical velocity management when the boat is at surface level. Try making it less aggressive:

```java
// In applyHullBuoyancy(), change the damping:
if (Math.abs(updated.y) < 0.003D) {
    this.setVelocity(updated.x, 0.0D, updated.z);
} else {
    this.setVelocity(updated.x, updated.y * 0.95D, updated.z);  // was 0.92 → 0.95
}
```

2. If the server is spiking, tick processing slows down, which directly reduces entity movement speed since velocity is applied per-tick. Fixing the server spikes (above) will inherently make the ship feel faster.

## The Hitbox Lag-Behind Getting Worse Over Time

This is directly caused by the server spike death spiral. When the server falls behind:
- The server processes ticks slower
- Entity position packets are delayed
- The client's interpolated position (rendered hull) drifts ahead of the server-sent AABB position
- The gap grows as the server falls further behind

Once you eliminate the server spikes, the hitbox lag will stay at the minimal 1-tick offset consistently, instead of progressively worsening.

## Summary

| Change | File | Why |
|--------|------|-----|
| Replace `super.tick()` with just `this.age++` | `ShipCollisionPartEntity.tick()` | Eliminates all vanilla Entity.tick() overhead (block collision, water state, movement, pushing) |
| Add `pushAwayFrom()` + `pushAway()` no-op overrides | `ShipCollisionPartEntity` | Prevents entity pushing calculations |
| `noClip = true` | `ShipCollisionPartEntity` constructor | Prevents vanilla from running collision resolution on parts |
| `0.92D → 0.95D` vertical damping | `ShipBoatEntity.applyHullBuoyancy()` | Less aggressive damping, smoother sailing |

The `super.tick()` removal is the critical change. It eliminates the entity processing overhead that causes the death spiral. The other changes are defensive reinforcements.
