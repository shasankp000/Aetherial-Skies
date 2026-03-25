# Custom Ship Architecture Design — Aetherial Skies (Fabric 1.20.1)

## Diagnosis of Current Problems

After analyzing the `1.20.1` branch (commit `46677a4` — "broken ship physics"), here are the root causes of the sinking/hitbox issues:

### Problem 1: Ship uses vanilla Boat dimensions (1.375×0.5625)
`ShipBoatEntity` extends `BoatEntity` and is registered with:
```java
EntityDimensions.fixed(1.375f, 0.5625f)  // vanilla boat size, FIXED dimensions
```
- **`fixed`** means `EntityDimensions.fixed = true`, so `refreshDimensions()` will never change the AABB.
- A 5×3×8 ship still has a 1.375-wide, 0.5625-tall hitbox for physics/water detection.

### Problem 2: Vanilla Boat buoyancy uses its own AABB to detect water
`BoatEntity.checkInWater()` and `BoatEntity.getWaterLevelAbove()` sample water blocks around the **entity's bounding box**. With a tiny 1.375×0.5625 box, the water surface detection is anchored to a point near the entity origin — not the actual hull footprint. This means:
- The ship sinks because the buoyancy force is calculated for a tiny volume.
- The ship's visual model extends far beyond the collision/buoyancy detection zone.

### Problem 3: Custom physics engine isn't connected to ShipBoatEntity
`ShipPhysicsEngine` exists but is never instantiated or ticked from `ShipBoatEntity.tick()`. The entity just calls `super.tick()` (vanilla boat logic) plus collision layer maintenance.

### Problem 4: ShipCollisionPartEntity handles collision but not buoyancy
The satellite collision parts create correct-sized AABBs around the hull, but they don't contribute to buoyancy at all — vanilla `BoatEntity` only checks water against its own AABB.

---

## Architecture Design

The core idea: **keep `BoatEntity` as the parent** (inheriting its motion, input handling, camera, and third-person sync), but **override the buoyancy/water-level system** to use the full hull dimensions, and **use `EntityDimensions.changing()` to allow runtime hitbox resizing**.

### Architecture Diagram

```
┌──────────────────────────────────────────────────────────────────┐
│                        ShipBoatEntity                            │
│  extends BoatEntity                                              │
│                                                                  │
│  ┌─────────────────────────┐  ┌──────────────────────────────┐  │
│  │  HullAwareBuoyancy      │  │  DynamicDimensions           │  │
│  │  ─────────────────────  │  │  ──────────────────────────  │  │
│  │  • Samples water across │  │  • EntityDimensions.changing │  │
│  │    full hull footprint  │  │  • getDimensions() override  │  │
│  │  • Computes waterline   │  │  • refreshDimensions() call  │  │
│  │    from ShipHullData    │  │  • AABB = hull bounding box  │  │
│  │  • Overrides floatBoat  │  │  • Recalculated on setHull   │  │
│  └─────────────────────────┘  └──────────────────────────────┘  │
│                                                                  │
│  ┌─────────────────────────┐  ┌──────────────────────────────┐  │
│  │  Vanilla Boat Inherit   │  │  Camera / Rider Sync         │  │
│  │  ─────────────────────  │  │  ──────────────────────────  │  │
│  │  • WASD input handling  │  │  • positionRider() override  │  │
│  │  • Paddle animation     │  │  • getPassengersRidingOffset │  │
│  │  • Entity networking    │  │  • Player sits at helm pos   │  │
│  │  • Status checks        │  │  • Camera follows ship yaw   │  │
│  └─────────────────────────┘  └──────────────────────────────┘  │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  ShipCollisionPartEntity[] (satellite collision layer)    │   │
│  │  • Positioned relative to ship yaw                        │   │
│  │  • Per-layer merged AABBs                                 │   │
│  │  • Prevents other entities from walking through hull      │   │
│  └──────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────┘
```

---

## Implementation Plan

### Step 1: Change Entity Registration to `changing` Dimensions

**File: `ModEntityTypes.java`**

```java
public static final EntityType<ShipBoatEntity> SHIP_BOAT_ENTITY =
        Registry.register(
                Registries.ENTITY_TYPE,
                new Identifier(AetherialSkies.MOD_ID, "ship_boat"),
                FabricEntityTypeBuilder.<ShipBoatEntity>create(SpawnGroup.MISC, ShipBoatEntity::new)
                        .dimensions(EntityDimensions.changing(1.375f, 0.5625f))
                        //                   ^^^^^^^^ was fixed(), now changing()
                        // This is the DEFAULT size (used before hull data is loaded).
                        // Once hull data arrives, getDimensions() returns the real size.
                        .trackRangeBlocks(128)
                        .trackedUpdateRate(3)  // faster sync for smoother motion
                        .build()
        );
```

**Why**: `EntityDimensions.changing()` sets `fixed = false`, which means whenever you call `this.refreshDimensions()`, the engine will call `getDimensions(EntityPose)` and rebuild the AABB. With `fixed()`, the AABB is frozen at registration time.

---

### Step 2: Override `getDimensions()` and `refreshDimensions()` in ShipBoatEntity

**File: `ShipBoatEntity.java`** — new fields and overrides:

```java
public class ShipBoatEntity extends BoatEntity {
    // ... existing tracked data fields ...
    
    // Cached hull-aware dimensions (recalculated when hull data changes)
    private EntityDimensions cachedDimensions = EntityDimensions.changing(1.375f, 0.5625f);
    
    // Hull bounding info for buoyancy
    private ShipHullData.HullBounds cachedBounds = null;
    
    // Waterline offset: how deep the ship sits in water
    // This is the Y-distance from the entity origin to the designed waterline.
    // Positive = waterline is above entity origin.
    private float waterlineOffset = 0.0f;
    
    /**
     * Called by the engine whenever dimensions might change.
     * We return our hull-derived dimensions so the AABB matches the ship.
     */
    @Override
    public EntityDimensions getDimensions(net.minecraft.entity.EntityPose pose) {
        return cachedDimensions;
    }
    
    /**
     * Recalculate dimensions from hull data.
     * Call this whenever hull data is set or updated.
     */
    private void recalculateDimensions() {
        ShipHullData hull = getHullData();
        if (hull.blocks().isEmpty()) {
            cachedDimensions = EntityDimensions.changing(1.375f, 0.5625f);
            cachedBounds = null;
            waterlineOffset = 0.0f;
        } else {
            ShipHullData.HullBounds bounds = hull.computeBounds();
            cachedBounds = bounds;
            
            // The entity AABB width = max of X-span and Z-span (AABB is always axis-aligned)
            // We take the diagonal for safety since ships rotate.
            float hullWidth = (float) Math.max(bounds.widthX(), bounds.widthZ());
            float hullHeight = (float) bounds.height();
            
            // For a rotatable ship, the AABB must encompass the ship at any yaw.
            // The worst case is when the ship is at 45 degrees:
            //   diagonal = sqrt(widthX^2 + widthZ^2)
            float diagonal = (float) Math.sqrt(
                bounds.widthX() * bounds.widthX() + bounds.widthZ() * bounds.widthZ()
            );
            
            cachedDimensions = EntityDimensions.changing(diagonal, hullHeight);
            
            // Waterline: we want roughly the bottom 30-40% of the hull to be submerged.
            // The vanilla boat sits with its origin at the water surface.
            // We set waterlineOffset so the bottom ~1/3 of the hull is below water.
            waterlineOffset = (float) (bounds.height() * 0.35);
        }
        
        // Tell the engine to rebuild the AABB from getDimensions()
        this.calculateDimensions();
    }
```

**Key insight**: `EntityDimensions` is axis-aligned, so for a ship that rotates, you need the **diagonal** of the hull as the width. This ensures the AABB always fully contains the visual hull at any yaw angle. The height stays as the hull height.

---

### Step 3: Override Buoyancy — The Core Fix for Sinking

The vanilla `BoatEntity` has three key methods for water interaction:
- `checkInWater()` — scans the entity's AABB for water blocks
- `getWaterLevelAbove()` — finds the water surface Y position
- `floatBoat()` — applies buoyancy force based on the above

With our expanded AABB, `checkInWater()` now scans the full hull footprint. But we need to override `floatBoat()` to account for the ship's mass and displaced volume from `ShipHullData`.

**File: `ShipBoatEntity.java`** — buoyancy overrides:

```java
    // ──────────────────────────────────────────────
    //  Hull-aware buoyancy system
    // ──────────────────────────────────────────────
    
    /**
     * The vanilla floatBoat() uses a hardcoded buoyancy for a 1-block boat.
     * We override to use the hull's hydrodynamic data.
     */
    @Override
    protected void floatBoat() {
        // If no hull data, fall back to vanilla behavior
        if (cachedBounds == null) {
            super.floatBoat();
            return;
        }
        
        ShipHullData hull = getHullData();
        float effectiveDensity = hull.effectiveRelativeDensity();
        float buoyancyAssist = hull.buoyancyAssist();
        
        // Get current status from vanilla (IN_WATER, UNDER_WATER, ON_LAND, etc.)
        // BoatEntity.Status is accessible since we extend BoatEntity.
        double gravity = -0.04;
        
        // Calculate how submerged the hull is
        float submersionRatio = calculateHullSubmersion();
        
        // Archimedes: buoyancy force = (water_density / ship_density) * gravity * submersion
        // For a ship that should float, effectiveDensity < 1.0 (lighter than water)
        // The buoyancy assist is an extra upward nudge for gameplay feel.
        double buoyancyForce = 0.0;
        if (submersionRatio > 0.0f) {
            // Scale buoyancy so that at equilibrium submersion, forces balance.
            // Target equilibrium: ~35% submerged for a wooden ship.
            float targetSubmersion = Math.min(0.9f, effectiveDensity);
            
            // Spring-like restoring force: pushes toward equilibrium waterline
            double displacement = submersionRatio - targetSubmersion;
            buoyancyForce = displacement * 0.12 + buoyancyAssist * 0.05;
            
            // Water drag (horizontal damping when in water)
            Vec3d vel = this.getVelocity();
            this.setVelocity(vel.x * 0.9, vel.y, vel.z * 0.9);
        }
        
        // Apply vertical force
        Vec3d currentVel = this.getVelocity();
        this.setVelocity(currentVel.x, currentVel.y + gravity + buoyancyForce, currentVel.z);
        
        // Dampen vertical oscillation for smooth settling
        if (Math.abs(this.getVelocity().y) < 0.003) {
            this.setVelocity(this.getVelocity().x, 0, this.getVelocity().z);
        } else {
            this.setVelocity(
                this.getVelocity().x,
                this.getVelocity().y * 0.92,  // vertical damping
                this.getVelocity().z
            );
        }
    }
    
    /**
     * Calculate what fraction of the hull volume is currently underwater.
     * Samples the actual hull block positions (rotated) against the world water level.
     */
    private float calculateHullSubmersion() {
        if (cachedBounds == null) return 0.0f;
        
        ShipHullData hull = getHullData();
        if (hull.blocks().isEmpty()) return 0.0f;
        
        int totalBlocks = hull.blocks().size();
        int submergedBlocks = 0;
        
        double yawRad = Math.toRadians(-this.getYaw());
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        
        for (ShipCrateService.PackedBlock block : hull.blocks()) {
            Vec3d offset = block.localOffset();
            // Rotate offset by ship yaw
            double worldX = this.getX() + (offset.x * cos - offset.z * sin);
            double worldY = this.getY() + offset.y;
            double worldZ = this.getZ() + (offset.x * sin + offset.z * cos);
            
            BlockPos blockPos = BlockPos.ofFloored(worldX, worldY, worldZ);
            if (!this.getWorld().getFluidState(blockPos).isEmpty()) {
                submergedBlocks++;
            }
        }
        
        return (float) submergedBlocks / totalBlocks;
    }
```

---

### Step 4: Camera and Rider Positioning

The vanilla boat already handles third-person camera smoothly. The key override is `positionRider()` so the player sits at the helm position, not at the tiny vanilla boat center.

```java
    // ──────────────────────────────────────────────
    //  Rider / Camera positioning
    // ──────────────────────────────────────────────
    
    @Override
    protected void updatePassengerPosition(Entity passenger, PositionUpdater positionUpdater) {
        if (!this.hasPassenger(passenger)) return;
        
        ShipHullData hull = getHullData();
        Vec3d helmOffset = hull.helmOffset();
        
        if (helmOffset.equals(Vec3d.ZERO) || hull.blocks().isEmpty()) {
            // Fallback to vanilla behavior if no hull data
            super.updatePassengerPosition(passenger, positionUpdater);
            return;
        }
        
        // Rotate helm offset by ship yaw
        double yawRad = Math.toRadians(-this.getYaw());
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        
        double riderX = this.getX() + (helmOffset.x * cos - helmOffset.z * sin);
        double riderZ = this.getZ() + (helmOffset.x * sin + helmOffset.z * cos);
        // Seat the player ~0.7 blocks above the helm block's base Y offset
        double riderY = this.getY() + helmOffset.y + 0.7;
        
        positionUpdater.accept(passenger, riderX, riderY, riderZ);
        
        // Clamp the rider's yaw to follow the ship (smooth camera)
        if (passenger instanceof net.minecraft.entity.player.PlayerEntity) {
            passenger.setBodyYaw(this.getYaw());
        }
    }
    
    /**
     * Vanilla calls this to decide how the camera orbits in third-person.
     * The default boat behavior works well; we just ensure the offset
     * accounts for the larger ship height.
     */
    @Override
    public double getPassengerRidingYOffset() {
        if (cachedBounds != null) {
            return cachedBounds.height() * 0.4;
        }
        return super.getPassengerRidingYOffset();
    }
```

**Camera sync** inherits automatically from `BoatEntity`:
- Vanilla third-person camera tracks `entity.getYaw()` and `entity.getPitch()`.
- The rider's view angle is clamped to the boat's yaw via `clampPassengerYaw()` in `BoatEntity`.
- Since `ShipBoatEntity extends BoatEntity`, all of this works out of the box.

---

### Step 5: Wire Up `setHullData()` to Trigger Dimension Recalculation

```java
    @Override
    public void setHullData(ShipHullData hullData) {
        // existing logic
        this.shipId = hullData.shipId();
        this.hullTag = hullData.toEntityTag();
        this.getDataTracker().set(TRACKED_SHIP_ID, this.shipId.toString());
        this.getDataTracker().set(TRACKED_HULL_TAG, this.hullTag.copy());
        
        // NEW: recalculate dimensions and AABB from hull data
        recalculateDimensions();
    }
    
    @Override
    public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        if (TRACKED_SHIP_ID.equals(data)) {
            try {
                this.shipId = UUID.fromString(this.getDataTracker().get(TRACKED_SHIP_ID));
            } catch (IllegalArgumentException ignored) {
                this.shipId = UUID.randomUUID();
            }
            return;
        }
        if (TRACKED_HULL_TAG.equals(data)) {
            this.hullTag = this.getDataTracker().get(TRACKED_HULL_TAG).copy();
            // NEW: client-side dimension recalculation when hull tag syncs
            recalculateDimensions();
        }
    }
    
    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        // ... existing NBT read logic ...
        
        // NEW: rebuild dimensions after loading
        recalculateDimensions();
    }
```

---

### Step 6: Updated `tick()` — Integrate Collision + Skip Vanilla Physics When Hull-Aware

```java
    @Override
    public void tick() {
        // Let BoatEntity handle input, status checks, networking, etc.
        super.tick();
        
        if (!this.getWorld().isClient()) {
            // Maintain collision satellite entities
            ShipCollisionLayerService.ensureLayer(this);
        }
        
        // Safety: re-clamp position if we detect terrain intersection
        // (prevents the ship from being pushed through blocks by vanilla boat logic)
        if (!this.getWorld().isClient() && cachedBounds != null) {
            clampToTerrain();
        }
    }
    
    /**
     * Prevent the ship from sinking into solid terrain.
     * Scans the hull footprint for solid blocks below and pushes up if needed.
     */
    private void clampToTerrain() {
        if (cachedBounds == null) return;
        
        double lowestHullY = this.getY() + cachedBounds.minY();
        BlockPos checkPos = BlockPos.ofFloored(this.getX(), lowestHullY - 0.1, this.getZ());
        
        if (!this.getWorld().getBlockState(checkPos).isAir() 
            && this.getWorld().getFluidState(checkPos).isEmpty()) {
            // Solid non-fluid block below hull — push up
            double pushY = checkPos.getY() + 1.0 - cachedBounds.minY() + 0.05;
            if (pushY > this.getY()) {
                this.setPosition(this.getX(), pushY, this.getZ());
                this.setVelocity(this.getVelocity().x, Math.max(0, this.getVelocity().y), this.getVelocity().z);
            }
        }
    }
```

---

### Step 7: Renderer Adjustments for Correct Visual Offset

The `ShipBoatEntityRenderer` currently renders blocks at their local offsets. With the expanded AABB, the entity origin may shift. The renderer should account for the hull's center-of-mass offset.

**File: `ShipBoatEntityRenderer.java`** — No changes needed to the block rendering logic, since blocks are already rendered at their `localOffset` relative to entity position. The `localOffset` values in `PackedBlock` are already computed relative to the center of mass in `ShipCompileService.packIntoCrate()`.

However, the `shadowRadius` should scale with the hull:

```java
    @Override
    public void render(ShipBoatEntity entity, float entityYaw, float tickDelta, 
                       MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        ShipHullData hullData = entity.getHullData();
        if (hullData.blocks().isEmpty()) return;
        
        // Dynamic shadow radius based on hull size
        ShipHullData.HullBounds bounds = hullData.computeBounds();
        this.shadowRadius = (float) Math.max(1.2, 
            Math.sqrt(bounds.widthX() * bounds.widthX() + bounds.widthZ() * bounds.widthZ()) * 0.5);
        
        matrices.push();
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-entityYaw));
        
        // ... existing block rendering loop (unchanged) ...
        
        matrices.pop();
    }
```

---

## Summary of Changes by File

| File | Change | Purpose |
|------|--------|---------|
| `ModEntityTypes.java` | `fixed()` → `changing()` for SHIP_BOAT_ENTITY | Enable runtime AABB resizing |
| `ShipBoatEntity.java` | Override `getDimensions()` | Return hull-based dimensions |
| `ShipBoatEntity.java` | Add `recalculateDimensions()` | Recompute AABB on hull change |
| `ShipBoatEntity.java` | Override `floatBoat()` | Hull-aware buoyancy with Archimedes |
| `ShipBoatEntity.java` | Add `calculateHullSubmersion()` | Sample water at actual hull positions |
| `ShipBoatEntity.java` | Override `updatePassengerPosition()` | Seat rider at helm position |
| `ShipBoatEntity.java` | Override `getPassengerRidingYOffset()` | Height-aware camera offset |
| `ShipBoatEntity.java` | Wire `recalculateDimensions()` into `setHullData()`, `onTrackedDataSet()`, `readCustomDataFromNbt()` | Keep dimensions in sync |
| `ShipBoatEntity.java` | Add `clampToTerrain()` in `tick()` | Prevent terrain sinking |
| `ShipBoatEntityRenderer.java` | Dynamic `shadowRadius` | Visual polish |

---

## Why This Architecture Works

### Inherits Vanilla Boat Motion + Camera
By extending `BoatEntity`, you get:
- WASD steering input handling (`controlBoat()`)
- Paddle animation and sounds
- Third-person camera smooth tracking (vanilla clamps rider yaw to boat yaw)
- Entity interpolation on client (smooth network position updates)
- `Status` enum (ON_LAND, IN_WATER, UNDER_WATER, IN_AIR, UNDER_FLOWING_WATER)

### Expandable Hitbox Without Sinking
- `EntityDimensions.changing()` + `getDimensions()` override = AABB grows to match hull.
- `floatBoat()` override uses the actual hull block positions to calculate submersion.
- Spring-based restoring force settles the ship at a stable waterline.
- `calculateHullSubmersion()` rotates hull blocks by ship yaw before checking water.

### Ship Doesn't Sink Into Water
The `floatBoat()` spring system pushes toward an equilibrium where ~35% of the hull is submerged (tunable via `effectiveRelativeDensity`). When the ship is too deep, buoyancy pushes it up. When too high, gravity pulls it down. Vertical damping (`*0.92`) prevents oscillation.

### Collision Layer Still Works
`ShipCollisionPartEntity` satellites continue to provide block-level collision for other entities. They don't interfere with the ship's own buoyancy since they already exclude ship-to-ship collision.

---

## Tuning Guide

| Parameter | Location | Effect |
|-----------|----------|--------|
| `0.12` in `displacement * 0.12` | `floatBoat()` | Buoyancy spring stiffness. Higher = faster correction, risk of oscillation |
| `0.92` vertical damping | `floatBoat()` | Oscillation damping. Lower = more damping, slower response |
| `0.9` horizontal damping | `floatBoat()` | Water drag. Lower = more drag when in water |
| `0.003` velocity threshold | `floatBoat()` | Threshold for zeroing vertical velocity (prevents jitter at rest) |
| `0.35` in `targetSubmersion` logic | `floatBoat()` | Default waterline depth as fraction of hull height |
| `effectiveRelativeDensity` | `ShipHullData` | Per-ship density from block composition. <1.0 floats, >1.0 sinks |
| `buoyancyAssist` | `ShipHullData` | Per-ship extra float force (wood planks contribute this) |

---

## Migration Checklist

- [ ] Change `EntityDimensions.fixed(1.375f, 0.5625f)` to `EntityDimensions.changing(1.375f, 0.5625f)` in `ModEntityTypes.java`
- [ ] Add `cachedDimensions`, `cachedBounds`, `waterlineOffset` fields to `ShipBoatEntity`
- [ ] Add `getDimensions(EntityPose)` override
- [ ] Add `recalculateDimensions()` method
- [ ] Wire `recalculateDimensions()` into `setHullData()`, `onTrackedDataSet()`, `readCustomDataFromNbt()`
- [ ] Override `floatBoat()` with hull-aware buoyancy
- [ ] Add `calculateHullSubmersion()` method
- [ ] Override `updatePassengerPosition()` for helm-based seating
- [ ] Override `getPassengerRidingYOffset()` for camera
- [ ] Add `clampToTerrain()` in `tick()`
- [ ] Update `ShipBoatEntityRenderer` shadow radius
- [ ] Test with various ship sizes (1×1, 3×3, 5×8, etc.)
- [ ] Tune buoyancy spring constants for feel

Sources:
- Minecraft 1.20.1 `EntityDimensions` API: `fixed` field controls whether `refreshDimensions()` is effective; `changing()` creates resizable dimensions (https://maven.fabricmc.net/docs/yarn-1.20.1-rc1+build.1/net/minecraft/entity/EntityDimensions.html)
- Forge forum discussion on dynamic hitbox at runtime (https://forums.minecraftforge.net/topic/115555-change-entitys-hitbox-in-game/)
- NeoForge discussion on custom boat entity registration (https://github.com/neoforged/NeoForge/discussions/1887)
- Valkyrien Skies Eureka collision box bug for reference on multiblock hitbox challenges (https://github.com/ValkyrienSkies/Eureka/issues/419)
