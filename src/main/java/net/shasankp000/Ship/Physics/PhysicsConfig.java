package net.shasankp000.Ship.Physics;

/**
 * Physics configuration constants for ship simulation.
 * These values are tuned to feel similar to vanilla falling blocks but with better control.
 */
public class PhysicsConfig {
    
    private PhysicsConfig() {
    }
    
    // Gravity acceleration (blocks per tick squared)
    // Vanilla falling block uses 0.04, we use 0.08 for more dramatic falling
    public static final float GRAVITY = 0.08f;
    
    // Air drag coefficient (reduces velocity each tick)
    // Higher = more air resistance = slower falling
    public static final float AIR_DRAG = 0.02f;
    
    // Water drag coefficient (reduces velocity when in water)
    // Much higher than air drag for realistic buoyancy effect
    public static final float WATER_DRAG = 0.1f;
    
    // Buoyancy force (upward acceleration when in water)
    // Applied per block of ship that's underwater
    public static final float BUOYANCY_PER_BLOCK = 0.025f;
    
    // Maximum velocity (prevents tunneling through terrain)
    public static final float MAX_VELOCITY = 50.0f;
    
    // Settling threshold (velocity below which ship is considered at rest)
    public static final float SETTLE_THRESHOLD = 0.01f;
    
    // Settling damping (reduces micro-oscillations when at rest)
    // Value < 1.0 causes velocity to decay
    public static final float SETTLE_DAMPING = 0.85f;
}

