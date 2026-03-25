package net.shasankp000.Ship.Physics;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Core physics engine for ship simulation.
 * Handles gravity, drag, buoyancy, and collision with terrain.
 * Independent of Valkyrien Skies - pure custom implementation.
 */
public class ShipPhysicsEngine {
    
    private final ShipPhysicsState state;
    private final World world;
    private final float shipVolume;  // Estimated volume for buoyancy calculation
    
    public ShipPhysicsEngine(ShipPhysicsState state, World world, float estimatedVolume) {
        this.state = state;
        this.world = world;
        this.shipVolume = estimatedVolume;
    }
    
    /**
     * Tick the physics simulation by one server tick (0.05 seconds)
     */
    public void tick() {
        // 1. Calculate buoyancy (how much of ship is underwater)
        float buoyancy = calculateBuoyancy();
        
        // 2. Apply forces
        // Gravity: always acts downward
        double gravityForce = -PhysicsConfig.GRAVITY * state.mass;
        
        // Drag: opposes velocity, increases with speed
        double dragCoefficient = PhysicsConfig.AIR_DRAG + (buoyancy * PhysicsConfig.WATER_DRAG);
        Vec3d dragForce = state.velocity.multiply(-dragCoefficient);
        
        // Buoyancy: upward force when in water
        double buoyancyForce = buoyancy * PhysicsConfig.BUOYANCY_PER_BLOCK * state.mass;
        
        // 3. Sum forces
        double totalYForce = gravityForce + dragForce.y + buoyancyForce;
        double totalXForce = dragForce.x;
        double totalZForce = dragForce.z;
        
        // 4. Calculate acceleration (F = ma, so a = F/m)
        double accelX = totalXForce / state.mass;
        double accelY = totalYForce / state.mass;
        double accelZ = totalZForce / state.mass;
        
        // 5. Update velocity
        state.velocity = new Vec3d(
                state.velocity.x + accelX,
                state.velocity.y + accelY,
                state.velocity.z + accelZ
        );
        
        // 6. Clip velocity to prevent tunneling
        state.velocity = clipVelocity(state.velocity);
        
        // 7. Update position
        state.position = state.position.add(state.velocity);
        
        // 8. Handle terrain collision (prevent sinking through ground)
        state.position = handleTerrainCollision(state.position);
        
        // 9. Apply settling damping to reduce jitter when at rest
        if (isAtRest()) {
            state.velocity = state.velocity.multiply(PhysicsConfig.SETTLE_DAMPING);
        }
    }
    
    /**
     * Calculate how submerged the ship is (0.0 = fully in air, 1.0 = fully in water)
     */
    private float calculateBuoyancy() {
        // Estimate ship volume by checking water blocks in bounding area
        // For now, use a simple height-based check
        
        Box shipBounds = new Box(
                state.position.x - 1.0,
                state.position.y - 1.0,
                state.position.z - 1.0,
                state.position.x + 1.0,
                state.position.y + 1.0,
                state.position.z + 1.0
        );
        
        int waterBlocksCount = countWaterBlocksInBox(shipBounds);
        int estimatedTotalBlocks = 26;  // Rough estimate, will be refined later
        
        return Math.min(1.0f, (float) waterBlocksCount / estimatedTotalBlocks);
    }
    
    /**
     * Count water blocks in a given box
     */
    private int countWaterBlocksInBox(Box box) {
        int count = 0;
        int minX = (int) Math.floor(box.minX);
        int maxX = (int) Math.ceil(box.maxX);
        int minY = (int) Math.floor(box.minY);
        int maxY = (int) Math.ceil(box.maxY);
        int minZ = (int) Math.floor(box.minZ);
        int maxZ = (int) Math.ceil(box.maxZ);
        
        // Sample a grid of points rather than checking every block
        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    net.minecraft.util.math.BlockPos pos = new net.minecraft.util.math.BlockPos(x, y, z);
                    if (!world.getFluidState(pos).isEmpty()) {
                        count++;
                    }
                }
            }
        }
        
        return count;
    }
    
    /**
     * Prevent velocity from exceeding maximum (prevents tunneling)
     */
    private Vec3d clipVelocity(Vec3d velocity) {
        double speed = velocity.length();
        if (speed > PhysicsConfig.MAX_VELOCITY) {
            return velocity.normalize().multiply(PhysicsConfig.MAX_VELOCITY);
        }
        return velocity;
    }
    
    /**
     * Check collision with terrain and prevent ship from sinking through ground
     */
    private Vec3d handleTerrainCollision(Vec3d newPos) {
        // Check if ship would intersect with terrain
        Box shipBounds = new Box(
                newPos.x - 1.0,
                newPos.y - 0.5,
                newPos.z - 1.0,
                newPos.x + 1.0,
                newPos.y + 1.0,
                newPos.z + 1.0
        );
        
        // Simple check: if center is in terrain, move up slightly
        int centerX = (int) Math.round(newPos.x);
        int centerY = (int) Math.round(newPos.y);
        int centerZ = (int) Math.round(newPos.z);
        
        if (!world.getBlockState(new net.minecraft.util.math.BlockPos(centerX, centerY, centerZ)).isAir()) {
            // Move ship up until it's not in terrain
            for (int i = 0; i < 10; i++) {
                newPos = newPos.add(0, 0.5, 0);
                centerY++;
                if (world.getBlockState(new net.minecraft.util.math.BlockPos(centerX, centerY, centerZ)).isAir()) {
                    // Stop upward movement when we exit terrain
                    state.velocity = new Vec3d(state.velocity.x, 0, state.velocity.z);
                    break;
                }
            }
        }
        
        return newPos;
    }
    
    /**
     * Check if ship is at rest (velocity near zero)
     */
    private boolean isAtRest() {
        return state.velocity.length() < PhysicsConfig.SETTLE_THRESHOLD;
    }
    
    // Getters
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


