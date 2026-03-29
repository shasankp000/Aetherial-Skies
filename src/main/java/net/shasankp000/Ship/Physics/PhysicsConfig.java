package net.shasankp000.Ship.Physics;

/**
 * Physics constants for the waterline-target model.
 *
 * The engine drives the hull bottom toward waterSurfaceY using a
 * PD controller (proportional + derivative) rather than a submersion
 * ratio spring.  This guarantees the ship settles at the waterline
 * regardless of density or pool depth.
 *
 * Tuning:
 *   BUOY_P  – proportional gain.  Higher = faster correction, but
 *              over-damped requires BUOY_D big enough.
 *   BUOY_D  – derivative (velocity) damping.  Critical damp condition:
 *              BUOY_D ≥ 2 * sqrt(BUOY_P).  We use ~3× for over-damping.
 *   With P=0.06, critical D = 2*sqrt(0.06) ≈ 0.49.  We use D=0.55.
 */
public class PhysicsConfig {

    private PhysicsConfig() {}

    /** Gravity acceleration (blocks/tick²). */
    public static final double GRAVITY          = 0.04D;

    /** Buoyancy proportional gain (spring toward waterline). */
    public static final double BUOY_P           = 0.06D;

    /**
     * Buoyancy derivative gain (damps velocity when near waterline).
     * Must satisfy BUOY_D >= 2*sqrt(BUOY_P) for over-damped settling.
     * 2*sqrt(0.06) = 0.49 → we use 0.55 (safely over-damped).
     */
    public static final double BUOY_D           = 0.55D;

    /**
     * How many blocks below the water surface the hull bottom should rest.
     * 0.0 = hull bottom exactly at waterline (boat floats on surface).
     * Positive = partially submerged draft.
     */
    public static final double TARGET_DRAFT     = 0.2D;

    /** Drag in air (multiplicative, per tick). */
    public static final double AIR_DRAG         = 0.02D;

    /** Extra drag when hull is touching water. */
    public static final double WATER_DRAG       = 0.15D;

    /** Hard velocity cap (blocks/tick). Prevents tunnelling. */
    public static final double MAX_VELOCITY     = 0.4D;

    /** Speed below which the ship is considered at rest. */
    public static final double SETTLE_THRESHOLD = 0.001D;

    /** Velocity multiplier applied when at rest. */
    public static final double SETTLE_DAMPING   = 0.3D;
}
