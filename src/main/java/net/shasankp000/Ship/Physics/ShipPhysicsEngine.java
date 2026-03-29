package net.shasankp000.Ship.Physics;

import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.shasankp000.AetherialSkies;
import net.shasankp000.Physics.JoltPhysicsSystem;
import net.shasankp000.Ship.ShipHullData;
import net.shasankp000.Ship.Structure.ShipStructure;
import net.shasankp000.Ship.Transform.ShipTransform;

import java.util.UUID;

/**
 * Waterline-target physics engine.
 *
 * Behaviour by environment:
 *
 *  ON WATER  — PD buoyancy + gravity drives the hull bottom to
 *              (waterSurfaceY - TARGET_DRAFT).  The ship bobs and
 *              settles at the waterline.
 *
 *  ON LAND   — No gravity, no velocity.  The ship sits stationary at
 *              its deploy Y, with preventFloorPenetration() ensuring it
 *              never clips into the ground.  This prevents the ship from
 *              free-falling off screen when deployed away from water.
 *
 * Each tick:
 *  1. Determine environment (water / land) via findWaterSurfaceY().
 *  2. If on water: PD controller + integrate + floor guard.
 *     If on land:  zero velocity + floor guard only.
 *  3. Push result into Jolt as a kinematic body move.
 *  4. After JoltPhysicsSystem.stepSimulation() (called by ShipTransformManager)
 *     read the authoritative position back from Jolt.
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

    private boolean inWater     = false;
    private boolean wasInWater  = false; // for transition logging

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

    public void tick() {
        refreshWaterSurface();

        double hullBottomY = hullBottomWorldY();
        inWater = !Double.isNaN(cachedWaterSurfaceY) && hullBottomY <= cachedWaterSurfaceY;

        // Log water / land transitions.
        if (inWater != wasInWater) {
            AetherialSkies.LOGGER.info(
                "[ShipPhysicsEngine] Ship {} {} water at y={}",
                shipId.toString().substring(0, 8),
                inWater ? "entered" : "left",
                String.format("%.2f", state.position.y));
            wasInWater = inWater;
        }

        if (inWater) {
            // ---- Water mode: PD buoyancy + gravity -----------------------
            double ay = -PhysicsConfig.GRAVITY;

            double targetY   = cachedWaterSurfaceY - PhysicsConfig.TARGET_DRAFT;
            double error     = targetY - hullBottomY;
            double a_buoy    = PhysicsConfig.BUOY_P * error
                             - PhysicsConfig.BUOY_D * state.velocity.y;
            ay += a_buoy;

            double newVy = state.velocity.y + ay;
            double drag  = PhysicsConfig.AIR_DRAG + PhysicsConfig.WATER_DRAG;
            newVy        = newVy            * (1.0 - drag);
            double newVx = state.velocity.x * (1.0 - drag);
            double newVz = state.velocity.z * (1.0 - drag);

            state.velocity = clipVelocity(new Vec3d(newVx, newVy, newVz));
            state.position = state.position.add(state.velocity);

            if (isAtRest()) {
                state.velocity = state.velocity.multiply(PhysicsConfig.SETTLE_DAMPING);
            }
        } else {
            // ---- Land mode: static — no gravity, no drift ---------------
            // Zero velocity so the ship doesn't slide or fall.
            state.velocity = Vec3d.ZERO;
            // preventFloorPenetration still runs below to push the hull up
            // if it spawned partially inside a block.
        }

        // Floor penetration guard runs in both modes.
        state.position = preventFloorPenetration(state.position);

        // Push into Jolt (kinematic move).
        if (hullData != null) {
            JoltPhysicsSystem.getInstance().updateBodyTransform(
                shipId, state.position, state.yaw, hullData);
        }
    }

    /**
     * Called by ShipTransformManager AFTER stepSimulation() to read back the
     * Jolt-authoritative position.
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
