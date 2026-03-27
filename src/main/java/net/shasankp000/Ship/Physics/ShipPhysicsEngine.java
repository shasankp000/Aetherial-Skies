package net.shasankp000.Ship.Physics;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.shasankp000.Entity.ShipBoatEntity;
import net.shasankp000.Ship.ShipHullData;

/**
 * Core physics engine for ship simulation.
 * Handles gravity, drag, buoyancy, and collision with terrain.
 */
public class ShipPhysicsEngine {

    private final ShipPhysicsState state;
    private final World world;

    private ShipHullData.HullBounds hullBounds = null;
    private ShipHullData hullData = null;

    private double cachedWaterSurfaceY = Double.NaN;
    private int waterSurfaceCacheTicks = 0;
    private static final int WATER_SURFACE_CACHE_TICKS = 5;

    public ShipPhysicsEngine(ShipPhysicsState state, World world, float estimatedVolume) {
        this.state = state;
        this.world = world;
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

    public void syncFrom(ShipBoatEntity ship) {
        state.position = ship.getPos();
        state.velocity = ship.getVelocity();
        state.yaw = ship.getYaw();
    }

    public void applyTo(ShipBoatEntity ship) {
        ship.setPosition(state.position.x, state.position.y, state.position.z);
        ship.setVelocity(state.velocity);
    }

    public void tick() {
        float s = calculateBuoyancy();

        double gravityForce = -PhysicsConfig.GRAVITY * state.mass;

        float targetSubmersion = (hullData != null)
                ? Math.max(0.01f, hullData.effectiveRelativeDensity())
                : 0.5f;

        double displacementError = s - targetSubmersion;
        double buoyancyForce = -displacementError * PhysicsConfig.BUOYANCY_STIFFNESS * state.mass;
        if (s <= 0.0f) {
            buoyancyForce = 0.0;
        }

        double dragCoefficient = PhysicsConfig.AIR_DRAG + (s * PhysicsConfig.WATER_DRAG);
        Vec3d dragForce = state.velocity.multiply(-dragCoefficient);

        double accelY = (gravityForce + buoyancyForce + dragForce.y) / state.mass;
        double accelX = dragForce.x / state.mass;
        double accelZ = dragForce.z / state.mass;

        state.velocity = new Vec3d(
                state.velocity.x + accelX,
                state.velocity.y + accelY,
                state.velocity.z + accelZ
        );

        state.velocity = clipVelocity(state.velocity);
        state.position = state.position.add(state.velocity);
        state.position = handleTerrainCollision(state.position);

        if (isAtRest()) {
            state.velocity = state.velocity.multiply(PhysicsConfig.SETTLE_DAMPING);
        }
    }

    /**
     * Returns submersion ratio (0.0 = fully in air, 1.0 = fully submerged)
     * using a hull height scan.
     */
    private float calculateBuoyancy() {
        if (hullBounds == null || hullData == null) {
            return 0.0f;
        }

        if (waterSurfaceCacheTicks <= 0 || Double.isNaN(cachedWaterSurfaceY)) {
            cachedWaterSurfaceY = findWaterSurfaceY();
            waterSurfaceCacheTicks = WATER_SURFACE_CACHE_TICKS;
        } else {
            waterSurfaceCacheTicks--;
        }

        double hullBottomWorldY = state.position.y + hullBounds.minY();
        double hullHeight = hullBounds.height();
        if (hullHeight <= 0.0D) {
            return 0.0f;
        }

        double submergedDepth = MathHelper.clamp(cachedWaterSurfaceY - hullBottomWorldY, 0.0D, hullHeight);
        return (float) (submergedDepth / hullHeight);
    }

    /**
     * Scans upward from below hull bottom to find water surface Y at ship center XZ.
     */
    private double findWaterSurfaceY() {
        double hullBottomWorldY = state.position.y + (hullBounds != null ? hullBounds.minY() : -0.5D);
        int scanX = MathHelper.floor(state.position.x);
        int scanZ = MathHelper.floor(state.position.z);
        int startY = MathHelper.floor(hullBottomWorldY) - 1;
        int maxScan = startY + (hullBounds != null ? MathHelper.ceil((float) hullBounds.height()) + 4 : 8);

        double lastWaterY = hullBottomWorldY;
        boolean sawWater = false;

        for (int y = startY; y <= maxScan; y++) {
            BlockPos pos = new BlockPos(scanX, y, scanZ);
            if (!world.getFluidState(pos).isEmpty()) {
                sawWater = true;
                lastWaterY = y + 1.0D;
            } else if (sawWater) {
                break;
            }
        }

        return lastWaterY;
    }

    private Vec3d clipVelocity(Vec3d velocity) {
        double speed = velocity.length();
        if (speed > PhysicsConfig.MAX_VELOCITY) {
            return velocity.normalize().multiply(PhysicsConfig.MAX_VELOCITY);
        }
        return velocity;
    }

    private Vec3d handleTerrainCollision(Vec3d newPos) {
        double minYOffset = hullBounds != null ? hullBounds.minY() : -0.5D;
        double widthRadius = hullBounds != null
                ? Math.max(hullBounds.widthX(), hullBounds.widthZ()) * 0.5D
                : 1.0D;
        double height = hullBounds != null ? hullBounds.height() : 1.0D;

        Box shipBounds = new Box(
                newPos.x - widthRadius,
                newPos.y + minYOffset,
                newPos.z - widthRadius,
                newPos.x + widthRadius,
                newPos.y + minYOffset + height,
                newPos.z + widthRadius
        );

        if (!world.isSpaceEmpty(null, shipBounds)) {
            for (int i = 0; i < 10; i++) {
                newPos = newPos.add(0.0D, 0.25D, 0.0D);
                shipBounds = shipBounds.offset(0.0D, 0.25D, 0.0D);
                if (world.isSpaceEmpty(null, shipBounds)) {
                    state.velocity = new Vec3d(state.velocity.x, Math.max(0.0D, state.velocity.y), state.velocity.z);
                    break;
                }
            }
        }

        return newPos;
    }

    private boolean isAtRest() {
        return state.velocity.lengthSquared() < (PhysicsConfig.SETTLE_THRESHOLD * PhysicsConfig.SETTLE_THRESHOLD);
    }

    public ShipPhysicsState getState() {
        return state;
    }

    public Vec3d getPosition() {
        return state.position;
    }

    public Vec3d getVelocity() {
        return state.velocity;
    }

    public float getYaw() {
        return state.yaw;
    }
}
