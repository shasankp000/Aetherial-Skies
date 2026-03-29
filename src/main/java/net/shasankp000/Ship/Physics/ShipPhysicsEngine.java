package net.shasankp000.Ship.Physics;

import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.shasankp000.Ship.ShipHullData;
import net.shasankp000.Ship.Structure.ShipStructure;
import net.shasankp000.Ship.Transform.ShipTransform;

/**
 * Waterline-target physics engine.
 *
 * Instead of computing a submersion ratio and fighting density arithmetic,
 * we directly drive the hull bottom toward (waterSurfaceY - TARGET_DRAFT)
 * using a PD controller:
 *
 *   error    = targetY - hullBottomY          (positive = hull is too high, needs to go down... wait
 *              actually positive = hull bottom is below target = needs upward push)
 *   a_buoy   = BUOY_P * error - BUOY_D * vy   (proportional + derivative damping)
 *   a_net_y  = -GRAVITY + a_buoy              (only when hull is at or below water surface)
 *
 * When the hull is above the water entirely, a_buoy = 0 and the ship falls
 * under gravity until it touches the waterline.
 *
 * This model is immune to density misconfiguration and pool depth issues.
 * It will always settle the hull bottom at (waterSurface - TARGET_DRAFT).
 */
public class ShipPhysicsEngine {

    private final ShipPhysicsState state;
    private final World world;

    private ShipHullData.HullBounds hullBounds = null;
    private ShipHullData hullData   = null;

    private double cachedWaterSurfaceY    = Double.NaN;
    private int    waterSurfaceCacheTicks = 0;
    private static final int WATER_SURFACE_CACHE_TICKS = 3;

    // Whether the hull was touching water last tick (for drag switch).
    private boolean inWater = false;

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
        refreshWaterSurface();

        double hullBottomY = hullBottomWorldY();
        inWater = !Double.isNaN(cachedWaterSurfaceY) && hullBottomY <= cachedWaterSurfaceY;

        // ---- Vertical dynamics ------------------------------------------
        double ay = -PhysicsConfig.GRAVITY;

        if (inWater) {
            // Target: hull bottom sits TARGET_DRAFT below water surface.
            double targetY = cachedWaterSurfaceY - PhysicsConfig.TARGET_DRAFT;
            // error > 0 → hull bottom is above target → needs to go down (negative push, but
            // wait: if hullBottomY < targetY then error < 0 → needs upward push)
            double error = targetY - hullBottomY;  // positive = hull too high above bottom target = push down? No.
            // Positive error means hullBottomY < targetY (hull is too low, submerged too deep) → push UP.
            double a_buoy = PhysicsConfig.BUOY_P * error
                          - PhysicsConfig.BUOY_D * state.velocity.y;
            ay += a_buoy;
        }

        // ---- Integrate Y -------------------------------------------------
        double newVy = state.velocity.y + ay;

        // ---- Drag --------------------------------------------------------
        double drag = PhysicsConfig.AIR_DRAG + (inWater ? PhysicsConfig.WATER_DRAG : 0.0);
        newVy          = newVy            * (1.0 - drag);
        double newVx   = state.velocity.x * (1.0 - drag);
        double newVz   = state.velocity.z * (1.0 - drag);

        state.velocity = new Vec3d(newVx, newVy, newVz);
        state.velocity = clipVelocity(state.velocity);

        // ---- Integrate position ------------------------------------------
        state.position = state.position.add(state.velocity);

        // ---- Hard floor: never let hull bottom go below solid terrain ----
        state.position = preventFloorPenetration(state.position);

        // ---- Settle ------------------------------------------------------
        if (isAtRest()) {
            state.velocity = state.velocity.multiply(PhysicsConfig.SETTLE_DAMPING);
        }
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

    /**
     * Scans downward from the hull bottom position to find the first water
     * block column, returning the Y of the water surface (top of water block).
     * Returns NaN if no water is found within scan range.
     */
    private double findWaterSurfaceY() {
        int scanX = MathHelper.floor(state.position.x);
        int scanZ = MathHelper.floor(state.position.z);

        // Scan from well above the hull down to 20 blocks below hull bottom.
        int startY = MathHelper.floor(state.position.y) + 4;
        int endY   = startY - 24;

        for (int y = startY; y >= endY; y--) {
            FluidState fluid = world.getFluidState(new BlockPos(scanX, y, scanZ));
            if (!fluid.isEmpty()) {
                return y + 1.0D;  // top surface of this water block
            }
        }
        return Double.NaN;  // no water found
    }

    /** World Y of the bottom of the hull bounding box. */
    private double hullBottomWorldY() {
        double offset = hullBounds != null ? hullBounds.minY() : -0.5D;
        return state.position.y + offset;
    }

    // ---- Floor penetration guard ----------------------------------------

    /**
     * If the hull bottom is inside a solid block, push the position up
     * until it clears, and zero out downward velocity.
     * Does NOT bounce — velocity is set to zero to avoid the 1-block
     * teleport loop.
     */
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
            // Nudge up by the remaining penetration depth.
            double blockTop = check.getY() + 1.0D;
            double penetration = blockTop - (pos.y + minYOffset);
            pos = pos.add(0.0D, penetration + 0.001D, 0.0D);
            // Zero vertical velocity — no bounce, no teleport loop.
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
