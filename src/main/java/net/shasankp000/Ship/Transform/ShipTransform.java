package net.shasankp000.Ship.Transform;

import net.minecraft.util.math.Vec3d;

/**
 * Immutable snapshot of a ship's spatial state at one point in time.
 *
 * structureOrigin — fixed centre of the block structure in ship_storage
 *                   coordinate space. Never changes after deployment.
 * worldOffset     — where structureOrigin appears in overworld space.
 *                   Updated by ShipPhysicsEngine every tick.
 * yaw             — rotation around the Y axis in degrees.
 * velocity        — current velocity (blocks/tick) used by physics and
 *                   client-side interpolation.
 */
public record ShipTransform(
    Vec3d structureOrigin,
    Vec3d worldOffset,
    float yaw,
    Vec3d velocity
) {
    /**
     * Converts a ship-local position (relative to structureOrigin) to
     * overworld space by applying the transform's offset and yaw.
     */
    public static Vec3d localToWorld(ShipTransform t, Vec3d localPos) {
        double rad = Math.toRadians(t.yaw());
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double rotX = localPos.x * cos - localPos.z * sin;
        double rotZ = localPos.x * sin + localPos.z * cos;
        return t.worldOffset().add(rotX, localPos.y, rotZ);
    }

    /**
     * Inverse of localToWorld. Converts an overworld position back to
     * ship-local space. Used by the interaction proxy to find the real
     * storage-dim block from a rendered hit position.
     */
    public static Vec3d worldToLocal(ShipTransform t, Vec3d worldPos) {
        Vec3d relative = worldPos.subtract(t.worldOffset());
        // Inverse rotation: negate yaw
        double rad = Math.toRadians(-t.yaw());
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double localX = relative.x * cos - relative.z * sin;
        double localZ = relative.x * sin + relative.z * cos;
        return new Vec3d(localX, relative.y, localZ);
    }

    /** Returns a new transform with updated motion fields, keeping structureOrigin fixed. */
    public ShipTransform withMotion(Vec3d newOffset, float newYaw, Vec3d newVelocity) {
        return new ShipTransform(this.structureOrigin, newOffset, newYaw, newVelocity);
    }
}
