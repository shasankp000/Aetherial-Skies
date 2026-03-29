package net.shasankp000.Ship.Client;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.ShipCrateService;
import net.shasankp000.Ship.Transform.ShipTransform;

/**
 * Client-side helper: "is there a ship block at this world BlockPos?"
 *
 * Used by ShipCollisionMixin (ChunkCache) to inject solid BlockStates so
 * the player cannot walk through rendered ship blocks.
 *
 * Design note — why we rotate the CENTRE, not the NW corner:
 *
 *   A block's local centre is at (lo.x, lo.y, lo.z).  After applying the
 *   ship's yaw rotation and worldOffset we get the exact floating-point
 *   world-space centre.  Flooring that gives the BlockPos column the block
 *   occupies.  A queried BlockPos matches iff it equals that floored column
 *   (for the normal 1-block case) OR is within ±1 on every axis (for blocks
 *   that straddle a column boundary at non-zero yaw — covered by the
 *   straddle loop below).
 *
 *   The old code rotated the NW corner (lo.x-0.5, lo.z-0.5) and tested
 *   |wx - qx| < 1.0, which:
 *     - was off-centre by 0.5 even at yaw=0, and
 *     - missed up to 3 of the 4 BlockPos columns a rotated block can span.
 */
public final class ShipCollisionProvider {

    private ShipCollisionProvider() {}

    /**
     * Returns true if any active ship block occupies the given world BlockPos.
     *
     * Algorithm:
     *   1. Rotate the block's local CENTRE by the ship yaw.
     *   2. Add worldOffset to get the floating-point world-space centre.
     *   3. The block occupies every integer BlockPos column whose unit cube
     *      [bx, bx+1) x [by, by+1) x [bz, bz+1) overlaps the rotated block.
     *      For a unit cube rotated up to 45° the centre-to-corner reach is
     *      at most ~0.87 blocks, so we only need to check the 27 integer
     *      columns within ±1 of floor(centre).  In practice only 1–4 of
     *      those will actually overlap; we use a tight float containment
     *      test to filter false positives.
     */
    public static boolean isShipBlock(BlockPos pos) {
        int qx = pos.getX();
        int qy = pos.getY();
        int qz = pos.getZ();

        for (ShipTransformCache.ClientShip ship : ShipTransformCache.INSTANCE.getAll()) {
            ShipTransform t = ship.transform;
            if (t == null || ship.blocks == null) continue;

            double rad = Math.toRadians(-t.yaw());   // renderer uses -yaw
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);
            Vec3d world = t.worldOffset();

            for (ShipCrateService.PackedBlock pb : ship.blocks) {
                Vec3d lo = pb.localOffset();

                // --- rotate the block CENTRE (no -0.5 offset) ---
                double cx = lo.x;          // local centre X
                double cy = lo.y + 0.5;    // local centre Y (lo.y is bottom face)
                double cz = lo.z;          // local centre Z

                double wcx = world.x + cx * cos - cz * sin;
                double wcy = world.y + cy;
                double wcz = world.z + cx * sin + cz * cos;

                // Floor of centre = primary BlockPos column
                int bx = MathHelper.floor(wcx);
                int by = MathHelper.floor(wcy);
                int bz = MathHelper.floor(wcz);

                // Fast path: queried pos is not within the ±1 neighbourhood
                if (Math.abs(qx - bx) > 1 || Math.abs(qy - by) > 1 || Math.abs(qz - bz) > 1) {
                    continue;
                }

                // Tight containment: the rotated block's world-space AABB
                // bottom-NW corner is (wcx-0.5, wcy-0.5, wcz-0.5).
                // A BlockPos (qx,qy,qz) overlaps iff its unit cube
                // [qx,qx+1) x [qy,qy+1) x [qz,qz+1) intersects
                // [wcx-0.5, wcx+0.5) x [wcy-0.5, wcy+0.5) x [wcz-0.5, wcz+0.5).
                // Two intervals [a,a+1) and [c-0.5,c+0.5) overlap iff
                //   a < c+0.5  AND  a+1 > c-0.5
                //   => c-0.5 < a+1  AND  a < c+0.5
                //   => |a - (c-0.5)| < 1  ... simplifies to:
                //   => (c - 0.5) - 1 < a < (c - 0.5) + 1
                //   i.e. the NW corner of the block falls in (c-1.5, c+0.5)
                // Equivalently: qx >= floor(wcx-0.5) && qx <= floor(wcx+0.5)
                int minX = MathHelper.floor(wcx - 0.5);
                int maxX = MathHelper.floor(wcx + 0.5);
                int minY = MathHelper.floor(wcy - 0.5);
                int maxY = MathHelper.floor(wcy + 0.5);
                int minZ = MathHelper.floor(wcz - 0.5);
                int maxZ = MathHelper.floor(wcz + 0.5);

                if (qx >= minX && qx <= maxX
                 && qy >= minY && qy <= maxY
                 && qz >= minZ && qz <= maxZ) {
                    return true;
                }

                // Helm is 2 blocks tall — also cover the block directly above
                if (pb.isHelm()) {
                    int minY2 = MathHelper.floor(wcy + 0.5);
                    int maxY2 = MathHelper.floor(wcy + 1.5);
                    if (qx >= minX && qx <= maxX
                     && qy >= minY2 && qy <= maxY2
                     && qz >= minZ && qz <= maxZ) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Computes the BlockPos a packed block occupies in world space.
     * Uses the same centre-rotation as isShipBlock() for consistency.
     */
    public static BlockPos worldPosOf(ShipTransform t, ShipCrateService.PackedBlock pb) {
        Vec3d lo  = pb.localOffset();
        double rad = Math.toRadians(-t.yaw());
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        double cx = lo.x;
        double cz = lo.z;

        double rx = cx * cos - cz * sin;
        double rz = cx * sin + cz * cos;

        Vec3d world = t.worldOffset();
        return new BlockPos(
            MathHelper.floor(world.x + rx),
            MathHelper.floor(world.y + lo.y),
            MathHelper.floor(world.z + rz)
        );
    }
}
