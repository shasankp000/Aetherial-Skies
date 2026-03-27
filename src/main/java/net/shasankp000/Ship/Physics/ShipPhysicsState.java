package net.shasankp000.Ship.Physics;

import net.minecraft.util.math.Vec3d;

/**
 * Holds the physics state of a ship (position, velocity, etc).
 * This is the core data structure that the physics engine operates on.
 */
public class ShipPhysicsState {
    
    public Vec3d position;
    public Vec3d velocity;
    public float yaw;
    public float angularVelocityYaw;
    
    public float mass;
    
    public ShipPhysicsState() {
        this.position = Vec3d.ZERO;
        this.velocity = Vec3d.ZERO;
        this.yaw = 0.0f;
        this.angularVelocityYaw = 0.0f;
        this.mass = 1.0f;
    }
    
    public ShipPhysicsState(Vec3d position, float mass) {
        this.position = position;
        this.velocity = Vec3d.ZERO;
        this.yaw = 0.0f;
        this.angularVelocityYaw = 0.0f;
        this.mass = mass;
    }
    
    public void copyFrom(ShipPhysicsState other) {
        this.position = other.position;
        this.velocity = other.velocity;
        this.yaw = other.yaw;
        this.angularVelocityYaw = other.angularVelocityYaw;
        this.mass = other.mass;
    }
    
    @Override
    public String toString() {
        return String.format("ShipPhysicsState{pos=%.1f,%.1f,%.1f vel=%.3f,%.3f,%.3f yaw=%.1f}",
                position.x, position.y, position.z,
                velocity.x, velocity.y, velocity.z,
                yaw);
    }
}
