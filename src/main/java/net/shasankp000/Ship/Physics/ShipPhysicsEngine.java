package net.shasankp000.Ship.Physics;

import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.shasankp000.Physics.JoltPhysicsSystem;
import net.shasankp000.Ship.ShipHullData;
import net.shasankp000.Ship.Structure.ShipStructure;
import net.shasankp000.Ship.Transform.ShipTransform;

import java.util.UUID;

/**
 * Waterline-target physics engine.
 *
 * Each tick:
 *  1. PD buoyancy/gravity controller computes new position + velocity.
 *  2. The result is pushed into Jolt as a kinematic body move
 *     (updateBodyTransform). Jolt then owns the position for collision
 *     purposes.
 *  3. After JoltPhysicsSystem.stepSimulation() (called by ShipTransformManager)
 *     we read the authoritative position back from Jolt via getBodyPosition().
 *     For kinematic bodies Jolt doesn't override our set position, but this
 *     round-trip makes the code future-proof for switching to dynamic mode.
 */
public class ShipPhysicsEngine {

    private final ShipPhysicsState state;
    private final World world;
    private final UUID shipId;

    private ShipHullData.HullBounds hullBounds = null;
    private ShipHullData hullData   = null;

    private double cachedWaterSurfaceY    = Double.NaN;
    private int    waterSurfaceCacheTicks = 0;
    private static final int WATER_SURFACE_CACHE_TICKS = 3;

    private boolean inWater = false;

    public ShipPhysicsEngine(UUID shipId, ShipPhysicsState state, World world) {
        this.shipId = shipId;
        this.state  = state;
        this.world  = world;
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

    /**
     * Runs the PD physics controller, pushes the result into Jolt, then reads
     * back the Jolt-authoritative position into state.position.
     * ShipTransformManager must call JoltPhysicsSystem.stepSimulation() after
     * all engine.tick() calls so the read-back reflects the stepped world.
     */
    public void tick() {
        refreshWaterSurface();

        double hullBottomY = hullBottomWorldY();
        inWater = !Double.isNaN(cachedWaterSurfaceY) && hullBottomY <= cachedWaterSurfaceY;

        // ---- Vertical dynamics ------------------------------------------
        double ay = -PhysicsConfig.GRAVITY;

        if (inWater) {
            double targetY   = cachedWaterSurfaceY - PhysicsConfig.TARGET_DRAFT;
            double error     = targetY - hullBottomY;
            double a_buoy    = PhysicsConfig.BUOY_P * error
                             - PhysicsConfig.BUOY_D * state.velocity.y;
            ay += a_buoy;
        }

        // ---- Integrate Y -------------------------------------------------
        double newVy = state.velocity.y + ay;

        // ---- Drag --------------------------------------------------------
        double drag = PhysicsConfig.AIR_DRAG + (inWater ? PhysicsConfig.WATER_DRAG : 0.0);
        newVy        = newVy            * (1.0 - drag);
        double newVx = state.velocity.x * (1.0 - drag);
        double newVz = state.velocity.z * (1.0 - drag);

        state.velocity = new Vec3d(newVx, newVy, newVz);
        state.velocity = clipVelocity(state.velocity);

        // ---- Integrate position ------------------------------------------
        state.position = state.position.add(state.velocity);

        // ---- Hard floor guard -------------------------------------------
        state.position = preventFloorPenetration(state.position);

        // ---- Settle ------------------------------------------------------
        if (isAtRest()) {
            state.velocity = state.velocity.multiply(PhysicsConfig.SETTLE_DAMPING);
        }

        // ---- Push into Jolt (kinematic move) ----------------------------
        // Jolt body tracks our computed position so collision broadphase is
        // always up to date. stepSimulation() is called by ShipTransformManager
        // after all ships have been updated.
        if (hullData != null) {
            JoltPhysicsSystem.getInstance().updateBodyTransform(
                shipId, state.position, state.yaw, hullData);
        }
    }

    /**
     * Called by ShipTransformManager AFTER stepSimulation() to read back the
     * Jolt-authoritative position. For kinematic bodies this is identical to
     * what we pushed in tick(), but having this call makes it trivial to
     * switch to dynamic mode later.
     */
    public void readBackFromJolt() {
        if (hullData == null) return;
        Vec3d joltPos = JoltPhysicsSystem.getInstance()
                .getBodyPosition(shipId, hullData, state.position);
        state.position = joltPos;
    }

    // ---- Water surface ---------------------------------------------------

    private void refreshWaterSurface() {
        if (waterSurfaceCacheTicks <= 0 || Double.isNaN(cachedWaterSurfaceY)) {
            cachedWaterSurfaceY = findWaterSurfaceY();
            waterSurfaceCacheTicks = WATER_SURFACE_CACHE_TICKS;
        } else {
            waterSurfaceCacheTicks--;
        }
    }

    private double findWaterSurfaceY() {
        int scanX = MathHelper.floor(state.position.x);
        int scanZ = MathHelper.floor(state.position.z);
        int startY = MathHelper.floor(state.position.y) + 4;
        int endY   = startY - 24;
        for (int y = startY; y >= endY; y--) {
            FluidState fluid = world.getFluidState(new BlockPos(scanX, y, scanZ));
            if (!fluid.isEmpty()) return y + 1.0D;
        }
        return Double.NaN;
    }

    private double hullBottomWorldY() {
        double offset = hullBounds != null ? hullBounds.minY() : -0.5D;
        return state.position.y + offset;
    }

    // ---- Floor penetration guard ----------------------------------------

    private Vec3d preventFloorPenetration(Vec3d pos) {
        if (hullBounds == null) return pos;
        double minYOffset = hullBounds.minY();
        for (int attempt = 0; attempt < 16; attempt++) {
            BlockPos check = new BlockPos(
                MathHelper.floor(pos.x),
                MathHelper.floor(pos.y + minYOffset),
                MathHelper.floor(pos.z)
            );
            BlockState bs = world.getBlockState(check);
            if (!bs.isSolidBlock(world, check)) break;
            double blockTop   = check.getY() + 1.0D;
            double penetration = blockTop - (pos.y + minYOffset);
            pos = pos.add(0.0D, penetration + 0.001D, 0.0D);
            state.velocity = new Vec3d(state.velocity.x, 0.0D, state.velocity.z);
        }
        return pos;
    }

    // ---- Helpers ---------------------------------------------------------

    private Vec3d clipVelocity(Vec3d v) {
        double speed = v.length();
        return speed > PhysicsConfig.MAX_VELOCITY
            ? v.normalize().multiply(PhysicsConfig.MAX_VELOCITY)
            : v;
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
