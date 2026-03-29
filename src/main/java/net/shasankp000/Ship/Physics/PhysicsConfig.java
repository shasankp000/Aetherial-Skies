package net.shasankp000.Ship.Physics;

/**
 * Physics configuration constants for ship simulation.
 *
 * Buoyancy model (proportional lift):
 *   buoyancy_accel = GRAVITY * clamp(s / targetSubmersion, 0, MAX_BUOYANCY_RATIO)
 *
 * This guarantees that at the target waterline the upward buoyancy exactly
 * cancels gravity, so the ship neither sinks nor rockets.
 * WATER_DRAG then damps any residual oscillation.
 */
public class PhysicsConfig {

    private PhysicsConfig() {}

    /** Gravity acceleration (blocks/tick²). */
    public static final double GRAVITY             = 0.04D;

    /**
     * Maximum buoyancy multiplier of gravity.
     * At s = targetSubmersion the ratio is 1.0 (exact balance).
     * At s = targetSubmersion * MAX_BUOYANCY_RATIO the force is capped.
     * Keeping this close to 1.0 prevents violent overcorrection upward.
     */
    public static final double MAX_BUOYANCY_RATIO  = 1.4D;

    /** Drag in air per tick (multiplicative: v *= 1 - AIR_DRAG). */
    public static final double AIR_DRAG            = 0.02D;

    /**
     * Additional drag per unit submersion when in water.
     * Must over-damp any residual oscillation.
     * Tuned so that a fully submerged ship loses ~35% of Y velocity per tick.
     */
    public static final double WATER_DRAG          = 0.35D;

    /**
     * Extra damping applied to Y velocity when the hull bottom hits the
     * terrain collision floor. Absorbs the bounce.
     */
    public static final double FLOOR_BOUNCE_DAMP   = 0.1D;

    /** Hard velocity cap (blocks/tick). */
    public static final double MAX_VELOCITY        = 0.5D;

    /** Speed below which settle-damping kicks in. */
    public static final double SETTLE_THRESHOLD    = 0.002D;

    /** Velocity multiplier applied when at rest (kills micro-oscillations). */
    public static final double SETTLE_DAMPING      = 0.4D;
}
