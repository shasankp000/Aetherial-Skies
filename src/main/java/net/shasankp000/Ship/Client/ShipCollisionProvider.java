package net.shasankp000.Ship.Client;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.ShipCrateService;
import net.shasankp000.Ship.Transform.ShipTransform;

/**
 * Client-side helper that answers: "is there a ship block at this world BlockPos?"
 *
 * Used by the collision mixin to inject solid VoxelShapes for rendered ship
 * blocks so the player cannot fall through the deck.
 *
 * How world position is computed (mirrors ShipStructureRenderer exactly):
 *   worldBlockPos = floor( worldOffset + rotate(localOffset - (0.5,0,0.5), yaw) )
 *
 * Thread safety: called from the render/game thread only (same as cache reads).
 */
public final class ShipCollisionProvider {

    private ShipCollisionProvider() {}

    /**
     * Returns true if any active ship has a block whose rendered world
     * position equals {@code pos}.
     */
    public static boolean isShipBlock(BlockPos pos) {
        for (ShipTransformCache.ClientShip ship : ShipTransformCache.INSTANCE.getAll()) {
            ShipTransform t = ship.transform;
            if (t == null) continue;
            for (ShipCrateService.PackedBlock pb : ship.blocks) {
                if (worldPosOf(t, pb).equals(pos)) return true;
            }
        }
        return false;
    }

    /**
     * Computes the BlockPos a packed block occupies in world space,
     * matching the renderer’s translation exactly.
     *
     *  renderer does:  translate(offset - cameraPos)
     *                  rotate yaw
     *                  translate(lo.x - 0.5, lo.y, lo.z - 0.5)
     *
     *  So the block’s bottom-NW corner in world space is:
     *    worldOffset + rotate(lo.x - 0.5, lo.y, lo.z - 0.5)
     *  and its BlockPos is floor of that.
     */
    static BlockPos worldPosOf(ShipTransform t, ShipCrateService.PackedBlock pb) {
        Vec3d lo  = pb.localOffset();
        double lx = lo.x - 0.5;
        double ly = lo.y;
        double lz = lo.z - 0.5;

        double rad = Math.toRadians(-t.yaw());   // renderer uses -yaw
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
