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
 * ON WATER: PD buoyancy + gravity drives hull bottom to
 *           (waterSurfaceY - TARGET_DRAFT). Ship bobs and settles.
 * ON LAND:  No gravity, no velocity. Ship sits static at deploy Y.
 *           preventFloorPenetration nudges it above any solid block
 *           it may have spawned inside, capped at 1 block/tick to
 *           prevent violent ejection.
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

    private boolean inWater    = false;
    private boolean wasInWater = false;

    public ShipPhysicsEngine(UUID shipId, ShipPhysicsState state, World world) {
        this.shipId = shipId;
        this.state  = state;
        this.world  = world;
    }

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

    public void tick() {
        refreshWaterSurface();

        double hullBottomY = hullBottomWorldY();
        inWater = !Double.isNaN(cachedWaterSurfaceY) && hullBottomY <= cachedWaterSurfaceY;

        if (inWater != wasInWater) {
            AetherialSkies.LOGGER.info(
                "[ShipPhysicsEngine] Ship {} {} water at y={}",
                shipId.toString().substring(0, 8),
                inWater ? "entered" : "left",
                String.format("%.2f", state.position.y));
            wasInWater = inWater;
        }

        if (inWater) {
            double ay      = -PhysicsConfig.GRAVITY;
            double targetY = cachedWaterSurfaceY - PhysicsConfig.TARGET_DRAFT;
            double error   = targetY - hullBottomY;
            double a_buoy  = PhysicsConfig.BUOY_P * error
                           - PhysicsConfig.BUOY_D * state.velocity.y;
            ay += a_buoy;

            double drag  = PhysicsConfig.AIR_DRAG + PhysicsConfig.WATER_DRAG;
            double newVy = (state.velocity.y + ay) * (1.0 - drag);
            double newVx = state.velocity.x * (1.0 - drag);
            double newVz = state.velocity.z * (1.0 - drag);

            state.velocity = clipVelocity(new Vec3d(newVx, newVy, newVz));
            state.position = state.position.add(state.velocity);

            if (isAtRest()) {
                state.velocity = state.velocity.multiply(PhysicsConfig.SETTLE_DAMPING);
            }
        } else {
            // Land mode: static, no gravity, no drift.
            state.velocity = Vec3d.ZERO;
        }

        // Floor penetration guard — capped to 1 block total push per tick.
        state.position = preventFloorPenetration(state.position);

        if (hullData != null) {
            JoltPhysicsSystem.getInstance().updateBodyTransform(
                shipId, state.position, state.yaw, hullData);
        }
    }

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
        int scanX  = MathHelper.floor(state.position.x);
        int scanZ  = MathHelper.floor(state.position.z);
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
    // Capped at MAX_PUSH_PER_TICK total upward displacement to prevent
    // violent multi-block ejection when spawning inside solid terrain.

    private static final double MAX_PUSH_PER_TICK = 1.0D;

    private Vec3d preventFloorPenetration(Vec3d pos) {
        if (hullBounds == null) return pos;
        double minYOffset  = hullBounds.minY();
        double totalPushed = 0.0D;

        for (int attempt = 0; attempt < 16; attempt++) {
            BlockPos check = new BlockPos(
                MathHelper.floor(pos.x),
                MathHelper.floor(pos.y + minYOffset),
                MathHelper.floor(pos.z)
            );
            BlockState bs = world.getBlockState(check);
            if (!bs.isSolidBlock(world, check)) break;

            double blockTop    = check.getY() + 1.0D;
            double penetration = blockTop - (pos.y + minYOffset);
            double push        = Math.min(penetration + 0.001D,
                                         MAX_PUSH_PER_TICK - totalPushed);
            if (push <= 0.0D) break;

            pos         = pos.add(0.0D, push, 0.0D);
            totalPushed += push;
            state.velocity = new Vec3d(state.velocity.x, 0.0D, state.velocity.z);

            if (totalPushed >= MAX_PUSH_PER_TICK) break;
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

    public ShipPhysicsState getState() { return state; }
    public Vec3d getPosition()         { return state.position; }
    public Vec3d getVelocity()         { return state.velocity; }
    public float getYaw()              { return state.yaw; }
}
