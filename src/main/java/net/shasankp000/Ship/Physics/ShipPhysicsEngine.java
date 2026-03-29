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

    /** Counts ticks since this engine was created; used for early debug logging. */
    private int tickCount = 0;
    private static final int DEBUG_TICKS = 10;

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

        // ---- First-N-ticks debug dump ------------------------------------
        if (tickCount < DEBUG_TICKS) {
            AetherialSkies.LOGGER.info(
                "[PhysDebug t={}] ship={} pos.y={} waterY={} hullBottomY={} inWater={} vel.y={} minY={}",
                tickCount,
                shipId.toString().substring(0, 8),
                String.format("%.4f", state.position.y),
                Double.isNaN(cachedWaterSurfaceY) ? "NaN" : String.format("%.4f", cachedWaterSurfaceY),
                String.format("%.4f", hullBottomY),
                inWater,
                String.format("%.4f", state.velocity.y),
                hullBounds != null ? String.format("%.4f", hullBounds.minY()) : "null"
            );
            tickCount++;
        }

        if (inWater) {
            double ay = -PhysicsConfig.GRAVITY;
            double targetY   = cachedWaterSurfaceY - PhysicsConfig.TARGET_DRAFT;
            double error     = targetY - hullBottomY;

            if (tickCount <= DEBUG_TICKS) {
                AetherialSkies.LOGGER.info(
                    "[PhysDebug t={}] targetY={} error={} a_buoy={}",
                    tickCount - 1,
                    String.format("%.4f", targetY),
                    String.format("%.4f", error),
                    String.format("%.6f", PhysicsConfig.BUOY_P * error - PhysicsConfig.BUOY_D * state.velocity.y)
                );
            }

            double a_buoy = PhysicsConfig.BUOY_P * error - PhysicsConfig.BUOY_D * state.velocity.y;
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
            state.velocity = Vec3d.ZERO;
        }

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
            double blockTop    = check.getY() + 1.0D;
            double penetration = blockTop - (pos.y + minYOffset);
            pos = pos.add(0.0D, penetration + 0.001D, 0.0D);
            state.velocity = new Vec3d(state.velocity.x, 0.0D, state.velocity.z);
        }
        return pos;
    }

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
