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
 * Tick order (critical for stability):
 *  1. Calculate submersion ratio s ∈ [0,1].
 *  2. Calculate net vertical acceleration:
 *       a_y = gravity_accel + buoyancy_accel
 *       where buoyancy_accel = -displacementError * STIFFNESS  (spring)
 *  3. Apply drag AFTER acceleration so it damps the result of this tick,
 *     not the velocity from last tick.  This is what prevents runaway.
 *  4. Clamp, integrate, collide.
 *
 * The water-drag coefficient is tuned to over-damp the buoyancy spring
 * (see PhysicsConfig for derivation).
 */
public class ShipPhysicsEngine {

    private final ShipPhysicsState state;
    private final World world;

    private ShipHullData.HullBounds hullBounds = null;
    private ShipHullData hullData   = null;

    private double cachedWaterSurfaceY = Double.NaN;
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
        float s = calculateBuoyancy();   // 0 = fully in air, 1 = fully submerged

        // ---- Vertical dynamics ------------------------------------------
        // Gravity always pulls down.
        double ay = -PhysicsConfig.GRAVITY;

        // Buoyancy spring: pushes up when too submerged, down when too high.
        // Only active when hull is touching water (s > 0).
        if (s > 0.0f) {
            float targetSubmersion = (hullData != null)
                ? Math.max(0.01f, hullData.effectiveRelativeDensity())
                : 0.5f;
            double displacementError = s - targetSubmersion;
            // Spring force (positive = up).
            ay += -displacementError * PhysicsConfig.BUOYANCY_STIFFNESS;
        }

        // Integrate Y acceleration into velocity.
        double newVy = state.velocity.y + ay;

        // ---- Drag --------------------------------------------------------
        // Applied AFTER acceleration so it damps this tick's result.
        // Water drag scales with submersion — more submerged = more damping.
        double dragY = PhysicsConfig.AIR_DRAG + (s * PhysicsConfig.WATER_DRAG);
        double dragXZ = PhysicsConfig.AIR_DRAG + (s * PhysicsConfig.WATER_DRAG * 0.5);

        newVy = newVy * (1.0 - dragY);
        double newVx = state.velocity.x * (1.0 - dragXZ);
        double newVz = state.velocity.z * (1.0 - dragXZ);

        state.velocity = new Vec3d(newVx, newVy, newVz);

        // ---- Clamp, integrate, collide -----------------------------------
        state.velocity = clipVelocity(state.velocity);
        state.position = state.position.add(state.velocity);
        state.position = handleTerrainCollision(state.position);

        // Kill micro-oscillations once nearly at rest.
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
        double hullHeight = hullBounds.height();
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

        double lastWaterY = hullBottomWorldY;
        boolean sawWater  = false;

        for (int y = startY; y <= maxScan; y++) {
            BlockPos pos = new BlockPos(scanX, y, scanZ);
            if (!world.getFluidState(pos).isEmpty()) {
                sawWater  = true;
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
                state.velocity = new Vec3d(
                    state.velocity.x, Math.max(0.0D, state.velocity.y), state.velocity.z);
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
