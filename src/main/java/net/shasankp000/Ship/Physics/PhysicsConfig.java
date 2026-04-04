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
 *   BUOY_P  – proportional gain (acceleration units, blocks/tick²/block-error).
 *              Must satisfy BUOY_P > GRAVITY at the expected operating error so
 *              the net upward acceleration can overcome gravity.
 *              At error=1 block, a_buoy = BUOY_P – GRAVITY must be > 0.
 *              Previous value (0.06) gave a_buoy = 0.06 – 0.04 = 0.02 at error=1,
 *              which was divided by mass (76), leaving only 0.00026/tick² — the
 *              ship would never float.
 *
 *   NOTE: a_buoy is NOT divided by mass.  BUOY_P/D operate on acceleration
 *         directly (like GRAVITY itself), so they are mass-independent.
 *         This matches how GRAVITY is applied in the engine (no mass division).
 *
 *   BUOY_D  – derivative (velocity) damping.  Critical damp condition:
 *              BUOY_D ≥ 2 * sqrt(BUOY_P).
 *              With P=0.15, critical D = 2*sqrt(0.15) ≈ 0.775.  We use D=0.85
 *              for over-damped (no oscillation) settling.
 */
public class PhysicsConfig {

    private PhysicsConfig() {}

    /** Gravity acceleration (blocks/tick²). */
    public static final double GRAVITY          = 0.04D;

    /**
     * Buoyancy proportional gain (acceleration, blocks/tick² per block of error).
     * Must be large enough that BUOY_P * error > GRAVITY for the ship to rise.
     * At the nominal operating error of ~1 block: net_a = 0.15 - 0.04 = +0.11/tick².
     */
    public static final double BUOY_P           = 0.15D;

    /**
     * Buoyancy derivative gain (damps vertical velocity near the waterline).
     * Over-damped condition: BUOY_D >= 2*sqrt(BUOY_P).
     * 2*sqrt(0.15) = 0.775 → we use 0.85 (safely over-damped, no bounce).
     */
    public static final double BUOY_D           = 0.85D;

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
