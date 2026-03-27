package net.shasankp000.Ship.Physics;

/**
 * Physics configuration constants for ship simulation.
 */
public class PhysicsConfig {

    private PhysicsConfig() {
    }

    // Buoyancy stiffness (controls the strength of the buoyant force)
    public static final double BUOYANCY_STIFFNESS = 0.12D;

    // Gravity acceleration (blocks per tick squared)
    // Reduced from 0.08 to 0.04 for less dramatic falling
    public static final double GRAVITY = 0.04D;

    // Air drag coefficient (reduces velocity each tick)
    // Slightly reduced to 0.01 for less air resistance
    public static final double AIR_DRAG = 0.01D;

    // Water drag coefficient (reduces velocity when in water)
    // Reduced to 0.05 for less resistance in water
    public static final double WATER_DRAG = 0.05D;

    // Maximum velocity (prevents tunneling through terrain)
    // Significantly reduced to 2.0 for slower maximum speed
    public static final double MAX_VELOCITY = 2.0D;

    // Settling threshold (velocity below which ship is considered at rest)
    // Reduced to 0.005 for finer detection of rest state
    public static final double SETTLE_THRESHOLD = 0.005D;

    // Settling damping (reduces micro-oscillations when at rest)
    // Reduced to 0.6 for quicker settling
    public static final double SETTLE_DAMPING = 0.6D;
}
