package net.shasankp000.Ship.Physics;

/**
 * Physics configuration constants for ship simulation.
 *
 * Tuning notes:
 *  - GRAVITY and BUOYANCY_FORCE must be equal for a ship at target submersion
 *    to produce zero net vertical acceleration. At equilibrium:
 *      buoyancy_force = GRAVITY  (both per unit mass, per tick²)
 *  - BUOYANCY_STIFFNESS controls how fast the ship returns to equilibrium.
 *    Keep it small (0.01–0.03) to avoid oscillation.
 *  - WATER_DRAG is the primary anti-oscillation damper. Must be large enough
 *    to critically damp the spring: drag > 2*sqrt(stiffness).
 *    With stiffness=0.02, critical drag ≈ 0.28 — we use 0.35 (over-damped).
 */
public class PhysicsConfig {

    private PhysicsConfig() {}

    /** Gravity acceleration (blocks/tick²). */
    public static final double GRAVITY          = 0.04D;

    /**
     * Buoyancy restoring-force stiffness.
     * Small value = gentle spring = stable settling.
     * Rule: must satisfy drag² > 4 * stiffness for over-damped behaviour.
     */
    public static final double BUOYANCY_STIFFNESS = 0.02D;

    /** Drag in air (per tick, fraction of velocity removed). */
    public static final double AIR_DRAG          = 0.02D;

    /**
     * Additional drag when submerged (scaled by submersion ratio).
     * Must be large enough to over-damp the buoyancy spring.
     * Critical value = 2*sqrt(BUOYANCY_STIFFNESS) ≈ 0.28.
     * We use 0.4 to be safely over-damped.
     */
    public static final double WATER_DRAG        = 0.40D;

    /** Hard velocity cap (blocks/tick) — prevents tunnelling. */
    public static final double MAX_VELOCITY      = 0.5D;

    /** Speed below which settle-damping kicks in (blocks/tick). */
    public static final double SETTLE_THRESHOLD  = 0.002D;

    /** Velocity multiplier applied each tick when at rest. */
    public static final double SETTLE_DAMPING    = 0.5D;
}
