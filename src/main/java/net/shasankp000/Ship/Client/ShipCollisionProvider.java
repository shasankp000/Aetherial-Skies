package net.shasankp000.Ship.Client;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.ShipCrateService;
import net.shasankp000.Ship.Transform.ShipTransform;

/**
 * Client-side helper that answers: "is there a ship block at this world BlockPos?"
 *
 * Used by ShipCollisionMixin (ChunkCache) to inject solid VoxelShapes for
 * rendered ship blocks so the player cannot walk through them.
 *
 * Key design: we use a floating-point AABB containment test rather than
 * comparing integer BlockPos values. The reason is that worldPosOf() floors
 * the rotated block corner, which at non-zero yaw can be off by 1 block and
 * cause the mixin to silently miss. The AABB test uses the exact sub-block
 * world-space position and checks if the queried pos falls within it, which
 * is correct at any yaw.
 */
public final class ShipCollisionProvider {

    private ShipCollisionProvider() {}

    /**
     * Returns true if any active ship block occupies the given world BlockPos.
     *
     * For each packed block we compute its exact world-space bottom-NW corner
     * (floating-point, no floor), then test whether {@code pos} is contained
     * in the resulting unit cube.
     *
     * Helm blocks (isHelm == true) are treated as 2-block-tall, so both the
     * base and the block directly above are considered solid.
     */
    public static boolean isShipBlock(BlockPos pos) {
        double qx = pos.getX();  // queried block column, integer coords
        double qy = pos.getY();
        double qz = pos.getZ();

        for (ShipTransformCache.ClientShip ship : ShipTransformCache.INSTANCE.getAll()) {
            ShipTransform t = ship.transform;
            if (t == null) continue;

            double rad = Math.toRadians(-t.yaw());  // renderer uses -yaw
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);
            Vec3d world = t.worldOffset();

            for (ShipCrateService.PackedBlock pb : ship.blocks) {
                Vec3d lo = pb.localOffset();

                // Exact (non-floored) world-space bottom-NW corner of this block
                double lx = lo.x - 0.5;
                double ly = lo.y;
                double lz = lo.z - 0.5;

                double wx = world.x + lx * cos - lz * sin;
                double wy = world.y + ly;
                double wz = world.z + lx * sin + lz * cos;

                // The block occupies the unit cube [wx, wx+1) x [wy, wy+1) x [wz, wz+1).
                // A BlockPos (qx,qy,qz) represents the cube [qx, qx+1) etc.
                // We test for overlap: two unit intervals [a, a+1) and [b, b+1)
                // overlap iff |a - b| < 1, i.e. b is in (a-1, a+1).
                if (Math.abs(wx - qx) < 1.0D
                 && Math.abs(wy - qy) < 1.0D
                 && Math.abs(wz - qz) < 1.0D) {
                    return true;
                }

                // Helm is 2 blocks tall — also cover the block above
                if (pb.isHelm() && Math.abs(wx - qx) < 1.0D
                               && Math.abs(wy - (qy - 1)) < 1.0D
                               && Math.abs(wz - qz) < 1.0D) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Computes the BlockPos a packed block occupies in world space,
     * matching the renderer's translation exactly (floor of rotated corner).
     *
     * Kept for consumers that need an integer BlockPos (e.g. ShipPassengerTracker).
     * For collision detection prefer isShipBlock() which uses the float AABB test.
     */
    public static BlockPos worldPosOf(ShipTransform t, ShipCrateService.PackedBlock pb) {
        Vec3d lo  = pb.localOffset();
        double lx = lo.x - 0.5;
        double ly = lo.y;
        double lz = lo.z - 0.5;

        double rad = Math.toRadians(-t.yaw());
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        double rx = lx * cos - lz * sin;
        double rz = lx * sin + lz * cos;

        Vec3d world = t.worldOffset();
        return new BlockPos(
            MathHelper.floor(world.x + rx),
            MathHelper.floor(world.y + ly),
            MathHelper.floor(world.z + rz)
        );
    }
}
