package net.shasankp000.Ship.Physics;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.shasankp000.Ship.ShipHullData;
import net.shasankp000.Ship.Structure.ShipStructure;
import net.shasankp000.Ship.Transform.ShipTransform;

/**
 * Core physics engine for ship simulation.
 *
 * Buoyancy model — proportional lift:
 *   buoyancy_accel = GRAVITY × clamp(s / targetSubmersion, 0, MAX_RATIO)
 *
 * At the equilibrium waterline (s == targetSubmersion) buoyancy exactly
 * cancels gravity.  WATER_DRAG damps the approach so the ship settles
 * smoothly without oscillating.
 *
 * Tick order:
 *  1. Compute submersion ratio s.
 *  2. Compute net Y acceleration (gravity + buoyancy).
 *  3. Integrate into velocity.
 *  4. Apply multiplicative drag AFTER integration (damps this tick's impulse).
 *  5. Clamp, integrate position, collide.
 */
public class ShipPhysicsEngine {

    private final ShipPhysicsState state;
    private final World world;

    private ShipHullData.HullBounds hullBounds = null;
    private ShipHullData hullData   = null;

    private double cachedWaterSurfaceY    = Double.NaN;
    private int    waterSurfaceCacheTicks = 0;
    private static final int WATER_SURFACE_CACHE_TICKS = 5;

    public ShipPhysicsEngine(ShipPhysicsState state, World world) {
        this.state = state;
        this.world = world;
    }

    // ---- Hull data -------------------------------------------------------

    public void setHullBounds(ShipHullData.HullBounds bounds) {
        this.hullBounds = bounds;
        this.cachedWaterSurfaceY = Double.NaN;
        this.waterSurfaceCacheTicks = 0;
    }

    public void setHullData(ShipHullData data) {
        this.hullData = data;
        this.cachedWaterSurfaceY = Double.NaN;
        this.waterSurfaceCacheTicks = 0;
    }

    // ---- Sync to/from ShipStructure --------------------------------------

    public void syncFrom(ShipStructure structure) {
        ShipTransform t = structure.getTransform();
        state.position = t.worldOffset();
        state.velocity = t.velocity();
        state.yaw      = t.yaw();
    }

    public void applyTo(ShipStructure structure) {
        structure.setTransform(
            structure.getTransform().withMotion(state.position, state.yaw, state.velocity)
        );
    }

    // ---- Tick ------------------------------------------------------------

    public void tick() {
        float  s              = calculateBuoyancy();   // 0 = in air, 1 = fully submerged
        double targetSub      = (hullData != null)
            ? Math.max(0.01, hullData.effectiveRelativeDensity())
            : 0.5;

        // ---- Net vertical acceleration -----------------------------------
        // Gravity always acts downward.
        double ay = -PhysicsConfig.GRAVITY;

        // Proportional buoyancy: lifts exactly as hard as gravity when the
        // hull is at the target waterline, less when higher, more when lower
        // (capped so it can’t fling the ship violently out of the water).
        if (s > 0.0f) {
            double ratio = MathHelper.clamp(
                s / targetSub,
                0.0,
                PhysicsConfig.MAX_BUOYANCY_RATIO
            );
            ay += PhysicsConfig.GRAVITY * ratio;  // net: 0 at equilibrium
        }

        // Integrate acceleration.
        double newVy = state.velocity.y + ay;

        // ---- Drag (applied after acceleration) ---------------------------
        double dragY  = PhysicsConfig.AIR_DRAG + (s * PhysicsConfig.WATER_DRAG);
        double dragXZ = PhysicsConfig.AIR_DRAG + (s * PhysicsConfig.WATER_DRAG * 0.3);

        newVy          = newVy            * (1.0 - dragY);
        double newVx   = state.velocity.x * (1.0 - dragXZ);
        double newVz   = state.velocity.z * (1.0 - dragXZ);

        state.velocity = new Vec3d(newVx, newVy, newVz);

        // ---- Clamp, integrate, collide -----------------------------------
        state.velocity = clipVelocity(state.velocity);
        state.position = state.position.add(state.velocity);
        state.position = handleTerrainCollision(state.position);

        if (isAtRest()) {
            state.velocity = state.velocity.multiply(PhysicsConfig.SETTLE_DAMPING);
        }
    }

    // ---- Internal helpers ------------------------------------------------

    private float calculateBuoyancy() {
        if (hullBounds == null || hullData == null) return 0.0f;

        if (waterSurfaceCacheTicks <= 0 || Double.isNaN(cachedWaterSurfaceY)) {
            cachedWaterSurfaceY = findWaterSurfaceY();
            waterSurfaceCacheTicks = WATER_SURFACE_CACHE_TICKS;
        } else {
            waterSurfaceCacheTicks--;
        }

        double hullBottomWorldY = state.position.y + hullBounds.minY();
        double hullHeight       = hullBounds.height();
        if (hullHeight <= 0.0D) return 0.0f;

        double submergedDepth = MathHelper.clamp(
            cachedWaterSurfaceY - hullBottomWorldY, 0.0D, hullHeight);
        return (float) (submergedDepth / hullHeight);
    }

    private double findWaterSurfaceY() {
        double hullBottomWorldY = state.position.y +
            (hullBounds != null ? hullBounds.minY() : -0.5D);
        int scanX  = MathHelper.floor(state.position.x);
        int scanZ  = MathHelper.floor(state.position.z);
        int startY = MathHelper.floor(hullBottomWorldY) - 1;
        int maxScan = startY + (hullBounds != null
            ? MathHelper.ceil((float) hullBounds.height()) + 4 : 8);

        double  lastWaterY = hullBottomWorldY;
        boolean sawWater   = false;

        for (int y = startY; y <= maxScan; y++) {
            BlockPos pos = new BlockPos(scanX, y, scanZ);
            if (!world.getFluidState(pos).isEmpty()) {
                sawWater   = true;
                lastWaterY = y + 1.0D;
            } else if (sawWater) {
                break;
            }
        }
        return lastWaterY;
    }

    private Vec3d clipVelocity(Vec3d v) {
        double speed = v.length();
        return speed > PhysicsConfig.MAX_VELOCITY
            ? v.normalize().multiply(PhysicsConfig.MAX_VELOCITY)
            : v;
    }

    private Vec3d handleTerrainCollision(Vec3d newPos) {
        if (hullBounds == null) return newPos;

        double minYOffset  = hullBounds.minY();
        double widthRadius = Math.max(hullBounds.widthX(), hullBounds.widthZ()) * 0.5D;
        double height      = hullBounds.height();

        BlockPos bottomPos = new BlockPos(
            MathHelper.floor(newPos.x),
            MathHelper.floor(newPos.y + minYOffset),
            MathHelper.floor(newPos.z)
        );
        BlockState bottomBlock = world.getBlockState(bottomPos);
        if (!bottomBlock.isSolidBlock(world, bottomPos)) return newPos;

        Box shipBounds = new Box(
            newPos.x - widthRadius, newPos.y + minYOffset, newPos.z - widthRadius,
            newPos.x + widthRadius, newPos.y + minYOffset + height, newPos.z + widthRadius
        );

        for (int i = 0; i < 10; i++) {
            newPos     = newPos.add(0.0D, 0.25D, 0.0D);
            shipBounds = shipBounds.offset(0.0D, 0.25D, 0.0D);
            BlockPos newBottom = new BlockPos(
                MathHelper.floor(newPos.x),
                MathHelper.floor(newPos.y + minYOffset),
                MathHelper.floor(newPos.z)
            );
            if (!world.getBlockState(newBottom).isSolidBlock(world, newBottom)) {
                // Kill downward momentum and absorb the bounce.
                state.velocity = new Vec3d(
                    state.velocity.x * (1.0 - PhysicsConfig.FLOOR_BOUNCE_DAMP),
                    0.0,   // hard-zero Y: no upward rebound from floor
                    state.velocity.z * (1.0 - PhysicsConfig.FLOOR_BOUNCE_DAMP)
                );
                break;
            }
        }
        return newPos;
    }

    private boolean isAtRest() {
        return state.velocity.lengthSquared() <
            PhysicsConfig.SETTLE_THRESHOLD * PhysicsConfig.SETTLE_THRESHOLD;
    }

    // ---- Accessors -------------------------------------------------------
    public ShipPhysicsState getState() { return state; }
    public Vec3d getPosition()         { return state.position; }
    public Vec3d getVelocity()         { return state.velocity; }
    public float getYaw()              { return state.yaw; }
}
