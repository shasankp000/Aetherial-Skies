package net.shasankp000.Ship.Physics;

/**
 * Tunable physics constants for the ship simulation.
 * These are the same values that lived in the old PhysicsConfig.java,
 * renamed to avoid collision with any old class that may linger.
 */
public final class ShipPhysicsConfig {

    private ShipPhysicsConfig() {}

    /** Gravitational acceleration (blocks/tick²). */
    public static final double GRAVITY = 0.04;

    /** Spring stiffness for buoyancy restoring force. */
    public static final double BUOYANCY_STIFFNESS = 1.8;

    /** Drag coefficient while in air. */
    public static final double AIR_DRAG = 0.01;

    /** Additional drag coefficient while submerged (scaled by submersion ratio). */
    public static final double WATER_DRAG = 0.12;

    /** Velocity above which motion is capped (blocks/tick). */
    public static final double MAX_VELOCITY = 2.0;

    /** Velocity below which the settle-damping kicks in (blocks/tick). */
    public static final double SETTLE_THRESHOLD = 0.005;

    /** Multiplier applied to velocity each tick when at rest. */
    public static final double SETTLE_DAMPING = 0.6;
}
