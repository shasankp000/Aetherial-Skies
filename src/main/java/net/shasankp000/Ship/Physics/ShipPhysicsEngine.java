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

    private int tickCount = 0;

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
        String sid = shipId.toString().substring(0, 8);
        int t = tickCount++;

        refreshWaterSurface();

        double hullBottomY = hullBottomWorldY();
        inWater = !Double.isNaN(cachedWaterSurfaceY) && hullBottomY <= cachedWaterSurfaceY;

        // ── ENTRY trace ──────────────────────────────────────────────────────
        AetherialSkies.LOGGER.info(
            "[PhysTrace t={}] ship={} ENTRY pos.y={} vel.y={} waterY={} hullBottomY={} inWater={}",
            t, sid,
            String.format("%.6f", state.position.y),
            String.format("%.6f", state.velocity.y),
            Double.isNaN(cachedWaterSurfaceY) ? "NaN" : String.format("%.6f", cachedWaterSurfaceY),
            String.format("%.6f", hullBottomY),
            inWater);

        if (inWater != wasInWater) {
            AetherialSkies.LOGGER.info(
                "[ShipPhysicsEngine] Ship {} {} water at y={}",
                sid, inWater ? "entered" : "left",
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

            AetherialSkies.LOGGER.info(
                "[PhysTrace t={}] ship={} PD targetY={} error={} a_buoy={} ay={}",
                t, sid,
                String.format("%.6f", targetY),
                String.format("%.6f", error),
                String.format("%.6f", a_buoy),
                String.format("%.6f", ay));

            double drag  = PhysicsConfig.AIR_DRAG + PhysicsConfig.WATER_DRAG;
            double newVy = (state.velocity.y + ay) * (1.0 - drag);
            double newVx = state.velocity.x * (1.0 - drag);
            double newVz = state.velocity.z * (1.0 - drag);

            state.velocity = clipVelocity(new Vec3d(newVx, newVy, newVz));
            state.position = state.position.add(state.velocity);

            AetherialSkies.LOGGER.info(
                "[PhysTrace t={}] ship={} AFTER_PD newVy={} pos.y={}",
                t, sid,
                String.format("%.6f", state.velocity.y),
                String.format("%.6f", state.position.y));

            if (isAtRest()) {
                state.velocity = state.velocity.multiply(PhysicsConfig.SETTLE_DAMPING);
                AetherialSkies.LOGGER.info("[PhysTrace t={}] ship={} AT_REST damping applied", t, sid);
            }
        } else {
            state.velocity = Vec3d.ZERO;
            AetherialSkies.LOGGER.info(
                "[PhysTrace t={}] ship={} LAND_MODE vel zeroed pos.y unchanged={}",
                t, sid, String.format("%.6f", state.position.y));
        }

        // ── Floor penetration guard ──────────────────────────────────────────
        double preFloorY = state.position.y;
        state.position = preventFloorPenetration(state.position);
        if (state.position.y != preFloorY) {
            AetherialSkies.LOGGER.info(
                "[PhysTrace t={}] ship={} FLOOR_PUSH preY={} postY={} delta={}",
                t, sid,
                String.format("%.6f", preFloorY),
                String.format("%.6f", state.position.y),
                String.format("%.6f", state.position.y - preFloorY));
        }

        AetherialSkies.LOGGER.info(
            "[PhysTrace t={}] ship={} EXIT pos.y={} vel.y={}",
            t, sid,
            String.format("%.6f", state.position.y),
            String.format("%.6f", state.velocity.y));

        if (hullData != null) {
            JoltPhysicsSystem.getInstance().updateBodyTransform(
                shipId, state.position, state.yaw, hullData);
        }
    }

    public void readBackFromJolt() {
        if (hullData == null) return;
        Vec3d joltPos = JoltPhysicsSystem.getInstance()
                .getBodyPosition(shipId, hullData, state.position);
        AetherialSkies.LOGGER.info(
            "[PhysTrace] ship={} JOLT_READBACK before={} after={} delta={}",
            shipId.toString().substring(0, 8),
            String.format("%.6f", state.position.y),
            String.format("%.6f", joltPos.y),
            String.format("%.6f", joltPos.y - state.position.y));
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
            boolean solid = bs.isSolidBlock(world, check);

            AetherialSkies.LOGGER.info(
                "[PhysTrace] ship={} FLOOR_CHECK attempt={} checkPos={} block={} solid={}",
                shipId.toString().substring(0, 8),
                attempt, check, bs.getBlock(), solid);

            if (!solid) break;

            double blockTop    = check.getY() + 1.0D;
            double penetration = blockTop - (pos.y + minYOffset);
            double push        = Math.min(penetration + 0.001D,
                                         MAX_PUSH_PER_TICK - totalPushed);
            if (push <= 0.0D) break;

            AetherialSkies.LOGGER.info(
                "[PhysTrace] ship={} FLOOR_PUSH_ITER attempt={} penetration={} push={} totalPushed={}",
                shipId.toString().substring(0, 8),
                attempt,
                String.format("%.6f", penetration),
                String.format("%.6f", push),
                String.format("%.6f", totalPushed + push));

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
