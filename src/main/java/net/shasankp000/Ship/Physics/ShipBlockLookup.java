package net.shasankp000.Ship.Physics;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.ShipCrateService;
import net.shasankp000.Ship.Structure.ShipStructure;
import net.shasankp000.Ship.Structure.ShipStructureManager;
import net.shasankp000.Ship.Transform.ShipTransform;

/**
 * Server-side equivalent of ShipCollisionProvider.
 *
 * Answers the question: "what BlockState should the server see at this
 * overworld BlockPos, accounting for all active ships?"
 *
 * Backed by ShipStructureManager (server singleton) — never touches the
 * client-only ShipTransformCache.
 *
 * Block-lookup algorithm (mirrors ShipCollisionProvider exactly):
 *   1. For every PackedBlock on every active ship:
 *      a. Rotate the block's local CENTRE by the ship's current yaw.
 *      b. Add worldOffset to get the floating-point world-space centre.
 *      c. Compute the integer BlockPos columns the rotated block overlaps
 *         using the same ±1 neighbourhood + tight AABB containment test.
 *   2. If the queried pos matches, return the block's real BlockState so
 *      Minecraft's collision engine sees correct geometry and shape.
 *
 * Thread safety:
 *   getAllShips() returns an unmodifiable view of a ConcurrentHashMap —
 *   safe to iterate from the server thread.
 */
public final class ShipBlockLookup {

    private ShipBlockLookup() {}

    /**
     * Returns the BlockState a ship contributes at {@code pos}, or
     * {@code null} if no ship occupies that position.
     *
     * The caller (the mixin) is responsible for only calling this when the
     * world's own getBlockState returned air or a fluid — we never override
     * real solid world blocks.
     */
    public static BlockState getShipBlockState(BlockPos pos) {
        int qx = pos.getX();
        int qy = pos.getY();
        int qz = pos.getZ();

        for (ShipStructure ship : ShipStructureManager.getInstance().getAllShips()) {
            ShipTransform t = ship.getTransform();
            if (t == null) continue;

            // Quick AABB pre-rejection: skip ships whose worldOffset is
            // more than (half-diagonal + 1) blocks away on any axis.
            // computeBounds() is cheap (iterates PackedBlocks once).
            var bounds = ship.getHullData().computeBounds();
            double halfDiag = Math.sqrt(
                bounds.widthX() * bounds.widthX() +
                bounds.height()  * bounds.height()  +
                bounds.widthZ() * bounds.widthZ()
            ) * 0.5 + 1.0;
            Vec3d wo = t.worldOffset();
            if (Math.abs(qx - wo.x) > halfDiag
             || Math.abs(qy - wo.y) > halfDiag
             || Math.abs(qz - wo.z) > halfDiag) {
                continue;
            }

            // NOTE: the renderer uses -yaw; worldToLocal also negates yaw.
            // ShipCollisionProvider uses Math.toRadians(-t.yaw()) for the
            // same reason — match that convention here.
            double rad = Math.toRadians(-t.yaw());
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);

            for (ShipCrateService.PackedBlock pb : ship.getHullData().blocks()) {
                Vec3d lo = pb.localOffset();

                // Block CENTRE in local space (lo.y is the bottom face)
                double cx = lo.x;
                double cy = lo.y + 0.5;
                double cz = lo.z;

                // Rotate centre and translate to world space
                double wcx = wo.x + cx * cos - cz * sin;
                double wcy = wo.y + cy;
                double wcz = wo.z + cx * sin + cz * cos;

                // Primary column
                int bx = MathHelper.floor(wcx);
                int by = MathHelper.floor(wcy);
                int bz = MathHelper.floor(wcz);

                // Fast neighbourhood reject
                if (Math.abs(qx - bx) > 1
                 || Math.abs(qy - by) > 1
                 || Math.abs(qz - bz) > 1) {
                    continue;
                }

                // Tight AABB containment (see ShipCollisionProvider for derivation)
                int minX = MathHelper.floor(wcx - 0.5);
                int maxX = MathHelper.floor(wcx + 0.5);
                int minY = MathHelper.floor(wcy - 0.5);
                int maxY = MathHelper.floor(wcy + 0.5);
                int minZ = MathHelper.floor(wcz - 0.5);
                int maxZ = MathHelper.floor(wcz + 0.5);

                if (qx >= minX && qx <= maxX
                 && qy >= minY && qy <= maxY
                 && qz >= minZ && qz <= maxZ) {
                    return resolveState(pb.blockId());
                }

                // Helm is 2 blocks tall — cover the block directly above
                if (pb.isHelm()) {
                    int minY2 = MathHelper.floor(wcy + 0.5);
                    int maxY2 = MathHelper.floor(wcy + 1.5);
                    if (qx >= minX && qx <= maxX
                     && qy >= minY2 && qy <= maxY2
                     && qz >= minZ && qz <= maxZ) {
                        return resolveState(pb.blockId());
                    }
                }
            }
        }
        return null; // no ship block here
    }

    // ---- Helpers ---------------------------------------------------------

    /**
     * Resolves a block ID string to its default BlockState.
     * Falls back to STONE if the block is not registered (should never
     * happen in practice since IDs came from the registry at pack-time).
     */
    private static BlockState resolveState(String blockId) {
        try {
            BlockState state = Registries.BLOCK
                .get(new Identifier(blockId))
                .getDefaultState();
            // Registries.BLOCK.get() returns AIR for unknown IDs — use
            // STONE as fallback so collision still works.
            if (state.isAir()) return Blocks.STONE.getDefaultState();
            return state;
        } catch (Exception e) {
            return Blocks.STONE.getDefaultState();
        }
    }
}
