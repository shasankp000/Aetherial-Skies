package net.shasankp000.Ship.Physics;

import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.Client.ShipTransformCache;
import net.shasankp000.Ship.ShipCrateService;
import net.shasankp000.Ship.Transform.ShipTransform;

/**
 * Shared, non-mixin helper for the deck-snap system.
 *
 * Both MixinLivingEntity (server) and MixinClientPlayerEntity (client) need
 * identical footprint and floor-Y logic. Because SpongePowered Mixin requires
 * all static methods inside a @Mixin class to be private, we cannot share
 * code between two mixin classes directly. This plain helper class holds the
 * logic once and both mixins call into it.
 */
public final class ShipDeckSnapHelper {

    private ShipDeckSnapHelper() {}

    public static final double SNAP_TOLERANCE = 1.1D;
    public static final double MAX_ABOVE      = 0.55D;
    /** Small margin added to hull AABB edges so the player doesn't slip off the very rim. */
    private static final double HULL_MARGIN   = 0.35D;

    /**
     * Finds the highest ship deck top-Y that the player is standing on
     * (or has just fallen through) given their world position.
     *
     * Returns Double.NaN if the player is not above any ship deck.
     */
    public static double findBestFloorY(double px, double pz, double feetY) {
        double best = Double.NaN;
        for (ShipTransformCache.ClientShip ship : ShipTransformCache.INSTANCE.getAll()) {
            ShipTransform t = ship.transform;
            if (t == null || ship.blocks == null || ship.blocks.isEmpty()) continue;

            double deckTopY = computeDeckTopY(ship, t);

            // Vertical snap window
            if (deckTopY > feetY + MAX_ABOVE) continue;
            if (deckTopY < feetY - SNAP_TOLERANCE) continue;

            if (isPlayerAboveShipLocal(px, pz, ship, t)) {
                if (Double.isNaN(best) || deckTopY > best) {
                    best = deckTopY;
                }
            }
        }
        return best;
    }

    /**
     * Finds the most-populated Y layer across all blocks (the main deck layer)
     * and returns its world-space top face Y.
     */
    public static double computeDeckTopY(ShipTransformCache.ClientShip ship, ShipTransform t) {
        java.util.HashMap<Integer, Integer> layerCount = new java.util.HashMap<>();
        for (ShipCrateService.PackedBlock pb : ship.blocks) {
            int iy = (int) Math.round(pb.localOffset().y);
            layerCount.merge(iy, 1, Integer::sum);
        }
        int deckLocalY = 0;
        int best = -1;
        for (java.util.Map.Entry<Integer, Integer> e : layerCount.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                deckLocalY = e.getKey();
            }
        }
        return t.worldOffset().y + deckLocalY + 1.0D;
    }

    /**
     * Checks if the world XZ point (px, pz) is within the ship's hull footprint
     * using a local-space AABB test that is correct at any yaw.
     *
     * Steps:
     *   1. Translate player XZ relative to the ship's worldOffset centre.
     *   2. Inverse-rotate by the ship's yaw to get local-space coordinates
     *      (renderer uses -yaw, so the inverse is +yaw).
     *   3. Compare against the local AABB built from all block offsets.
     */
    public static boolean isPlayerAboveShipLocal(double px, double pz,
            ShipTransformCache.ClientShip ship, ShipTransform t) {

        // 1. Translate
        double dx = px - t.worldOffset().x;
        double dz = pz - t.worldOffset().z;

        // 2. Inverse-rotate (+yaw)
        double rad = Math.toRadians(t.yaw());
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double localX = dx * cos - dz * sin;
        double localZ = dx * sin + dz * cos;

        // 3. Build local AABB and test
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (ShipCrateService.PackedBlock pb : ship.blocks) {
            Vec3d lo = pb.localOffset();
            double bx = lo.x - 0.5D;
            double bz = lo.z - 0.5D;
            if (bx       < minX) minX = bx;
            if (bx + 1.0D > maxX) maxX = bx + 1.0D;
            if (bz       < minZ) minZ = bz;
            if (bz + 1.0D > maxZ) maxZ = bz + 1.0D;
        }

        return localX >= minX - HULL_MARGIN && localX <= maxX + HULL_MARGIN
            && localZ >= minZ - HULL_MARGIN && localZ <= maxZ + HULL_MARGIN;
    }
}
