# Nuclear Fix — Disable Collision Parts Entirely (For Now)

## Why Nothing Has Fixed the Server Spikes

We've been optimizing the collision parts' own tick, their owner resolution, and search radii. But the actual performance cost isn't in the parts' code — it's in **vanilla's entity collision system processing them on behalf of every other entity in the world**.

When `ShipCollisionPartEntity.isCollidable()` returns `true`, vanilla treats it as a hard collision body (like a boat or shulker). Every entity that calls `Entity.move()` — the player, every mob, every item, every XP orb within loaded chunks — runs `World.getEntityCollisions(movingEntity, expandedBox)` which iterates all entities near the movement path and checks `entity.isCollidable()`. For each collidable entity it finds, it adds a VoxelShape collision check.

Your collision parts have large merged-layer AABBs (a 5×5 ship layer is a ~5×1×5 block collision box). This is a massive collidable entity by Minecraft standards. Every time ANY entity moves near the ship, it runs full AABB collision resolution against this box. With the player standing on the ship, the player's own `move()` call every tick checks collision against the parts — **and gets stuck**, which is why you can't move on the deck.

The 32-second freeze pattern: initial 2-second spike → catch-up ticks → each catch-up tick processes player movement against collidable parts → each resolution is expensive → more ticks pile up → death spiral.

## The Fix: Disable Collision Parts, Remove the System for Now

The collision layer system as currently designed is fundamentally incompatible with Minecraft's entity collision architecture at scale. A single `isCollidable() = true` entity with a large AABB causes O(N) per-tick collision checks for every nearby entity, and the player can't walk on them anyway (they get stuck).

**Disable the entire collision layer system.** The ship will rely on the vanilla boat AABB for its own collision and the rendered blocks for visual hull. External collision (preventing mobs from walking through the ship) can be revisited later with a different approach (perhaps block-based or mixin-based).

### Changes:

**1. In `ShipBoatEntity.tick()` — remove `ensureLayer()` call:**
```java
@Override
public void tick() {
    super.tick();
    applyHullBuoyancy();
    if (!this.getWorld().isClient()) {
        // ShipCollisionLayerService.ensureLayer(this);  ← REMOVE
        clampToTerrain();
    }
}
```

**2. In `ShipDeployService.deployFromCrate()` — remove `rebuildLayer()` call:**
```java
if (!world.spawnEntity(shipBoat)) {
    return new DeployResult(false, "Failed to spawn ship.");
}
// ShipCollisionLayerService.rebuildLayer(shipBoat);  ← REMOVE
```

**3. In `ShipBoatEntity.remove()` — remove `removeLayer()` call:**
```java
@Override
public void remove(RemovalReason reason) {
    // Remove collision layer cleanup — no layers to clean up
    super.remove(reason);
}
```

**4. In `ShipCollisionPartEntity.isCollidable()` — return false as safety:**
```java
@Override
public boolean isCollidable() {
    return false;  // was true — disabled until a proper collision system is designed
}
```

This will immediately:
- Eliminate ALL server spikes (no collidable entities to process)
- Let the player walk freely while riding (no collision parts blocking movement)
- Restore normal ship movement speed
- Keep the hitbox tightly synced (no death spiral degrading sync)

The ship will still float, steer, render correctly, and carry passengers. The only thing lost is external collision (mobs could walk through the hull), which wasn't working well anyway since the player was also getting stuck.

## Future: Proper Ship Collision

When you're ready to revisit hull collision, the approaches that actually work in Minecraft are:

1. **Invisible barrier blocks**: Place/remove actual `minecraft:barrier` blocks at hull positions as the ship moves. This uses vanilla's native block collision (which is highly optimized) instead of entity collision. The downside is you need to clean up barriers when the ship moves away.

2. **Shulker-style collision**: Vanilla shulkers are the only entities that create truly solid collision. Study how `ShulkerEntity` implements its collision box — it uses a very specific pattern with `getCollisionBox()` that's optimized in vanilla.

3. **Per-entity `getHardCollisionBox(Entity)`**: If this method exists in your Yarn version, override it on the collision parts to return `null` for the player riding the ship and the ship itself, while returning the AABB for other entities. This gives selective collision without blocking the ship or its rider.

For now, disabling the system entirely is the right call. Get the ship sailing smoothly first, then add hull collision as a separate feature.
