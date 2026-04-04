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

    // Cached once via setHullBounds / setHullData — never recomputed per tick.
    private ShipHullData.HullBounds hullBounds = null;
    private ShipHullData hullData   = null;

    private double cachedWaterSurfaceY    = Double.NaN;
    private int    waterSurfaceCacheTicks = 0;
    private static final int WATER_SURFACE_CACHE_TICKS = 3;
    private static final int MIN_OPEN_WATER_DEPTH = 2;

    private boolean inWater    = false;
    private boolean wasInWater = false;

    private ShipSteerInput steerInput = ShipSteerInput.NONE;

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

    public void setSteerInput(ShipSteerInput input) {
        this.steerInput = (input != null) ? input : ShipSteerInput.NONE;
    }

    public void syncFrom(ShipStructure structure) {
        // Use pre-computed cached bounds from ShipStructure.
        setHullBounds(structure.getCachedBounds());
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

            double a_buoy = MathHelper.clamp(
                PhysicsConfig.BUOY_P * error - PhysicsConfig.BUOY_D * state.velocity.y,
                -PhysicsConfig.GRAVITY * 3.0, PhysicsConfig.GRAVITY * 3.0);
            ay += a_buoy;

            double yawRad = Math.toRadians(state.yaw);
            double fwdX   = -MathHelper.sin((float) yawRad);
            double fwdZ   =  MathHelper.cos((float) yawRad);
            double ax = fwdX * steerInput.forward() * PhysicsConfig.THRUST_ACCEL;
            double az = fwdZ * steerInput.forward() * PhysicsConfig.THRUST_ACCEL;

            float newYaw = state.yaw + (float)(steerInput.turn() * PhysicsConfig.TURN_RATE_DEG);
            state.yaw = ((newYaw % 360f) + 360f) % 360f;

            double totalDrag = PhysicsConfig.AIR_DRAG + PhysicsConfig.WATER_DRAG + PhysicsConfig.HORIZ_DRAG;
            double newVy = (state.velocity.y + ay)  * (1.0 - (PhysicsConfig.AIR_DRAG + PhysicsConfig.WATER_DRAG));
            double newVx = (state.velocity.x + ax)  * (1.0 - totalDrag);
            double newVz = (state.velocity.z + az)  * (1.0 - totalDrag);

            state.velocity = clipVelocity(new Vec3d(newVx, newVy, newVz));
            state.position = state.position.add(state.velocity);

            if (isAtRest()) {
                state.velocity = state.velocity.multiply(PhysicsConfig.SETTLE_DAMPING);
            }

        } else {
            state.velocity = Vec3d.ZERO;
            state.position = preventFloorPenetration(state.position);
        }

        if (hullData != null) {
            JoltPhysicsSystem.getInstance().updateBodyTransform(
                shipId, state.position, state.yaw, hullData);
        }
    }

    public void readBackFromJolt() {
        if (hullData == null) return;
        state.position = JoltPhysicsSystem.getInstance()
                .getBodyPosition(shipId, hullData, state.position);
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
        int scanX  = MathHelper.floor(state.position.x);
        int scanZ  = MathHelper.floor(state.position.z);
        int startY = MathHelper.floor(state.position.y) + 4;
        int endY   = startY - 24;

        for (int y = startY; y >= endY; y--) {
            FluidState fluid = world.getFluidState(new BlockPos(scanX, y, scanZ));
            if (!fluid.isEmpty()) {
                int consecutive = 0;
                for (int dy = 0; dy < MIN_OPEN_WATER_DEPTH; dy++) {
                    if (!world.getFluidState(new BlockPos(scanX, y - dy, scanZ)).isEmpty())
                        consecutive++;
                    else break;
                }
                if (consecutive >= MIN_OPEN_WATER_DEPTH) return y + 1.0D;
            }
        }
        return Double.NaN;
    }

    private double hullBottomWorldY() {
        double offset = hullBounds != null ? hullBounds.minY() : -0.5D;
        return state.position.y + offset;
    }

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
            double push        = Math.min(penetration + 0.001D, MAX_PUSH_PER_TICK - totalPushed);
            if (push <= 0.0D) break;
            pos         = pos.add(0.0D, push, 0.0D);
            totalPushed += push;
            state.velocity = new Vec3d(state.velocity.x, 0.0D, state.velocity.z);
            if (totalPushed >= MAX_PUSH_PER_TICK) break;
        }
        return pos;
    }

    private Vec3d clipVelocity(Vec3d v) {
        double speed = v.length();
        return speed > PhysicsConfig.MAX_VELOCITY ? v.normalize().multiply(PhysicsConfig.MAX_VELOCITY) : v;
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
