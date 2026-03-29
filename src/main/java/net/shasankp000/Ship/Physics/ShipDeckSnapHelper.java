package net.shasankp000.Ship.Physics;

import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.Client.ShipTransformCache;
import net.shasankp000.Ship.ShipCrateService;
import net.shasankp000.Ship.Transform.ShipTransform;

/**
 * Shared, non-mixin helper for the deck-snap system.
 *
 * Handles two cases:
 *  1. Player is ON TOP of the deck (or just fell through) -> snap UP to deckTopY.
 *  2. Player is INSIDE the hull (swam up from below)      -> eject DOWN to hullBottomY.
 *
 * Both MixinLivingEntity (server) and MixinClientPlayerEntity (client) call
 * these methods so client and server always agree, preventing rubber-band.
 */
public final class ShipDeckSnapHelper {

    private ShipDeckSnapHelper() {}

    public static final double SNAP_TOLERANCE = 1.1D;
    public static final double MAX_ABOVE      = 0.55D;
    private static final double HULL_MARGIN   = 0.35D;

    // -----------------------------------------------------------------------
    // TOP-SNAP: keep player ON the deck surface
    // -----------------------------------------------------------------------

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

            if (deckTopY > feetY + MAX_ABOVE) continue;
            if (deckTopY < feetY - SNAP_TOLERANCE) continue;

            if (isPlayerInFootprint(px, pz, ship, t)) {
                if (Double.isNaN(best) || deckTopY > best) {
                    best = deckTopY;
                }
            }
        }
        return best;
    }

    // -----------------------------------------------------------------------
    // BOTTOM-EJECT: push player OUT when inside the hull volume
    // -----------------------------------------------------------------------

    /**
     * Returns the world-space bottom face Y of the ship hull if the player
     * is currently INSIDE the hull volume (horizontally within footprint AND
     * vertically between hullBottomY and deckTopY).
     *
     * Returns Double.NaN if the player is not inside any hull.
     *
     * Caller should push the player's Y down to this value and zero their
     * upward velocity so they cannot swim back in.
     */
    public static double findHullEjectY(double px, double pz, double feetY) {
        for (ShipTransformCache.ClientShip ship : ShipTransformCache.INSTANCE.getAll()) {
            ShipTransform t = ship.transform;
            if (t == null || ship.blocks == null || ship.blocks.isEmpty()) continue;

            double deckTopY   = computeDeckTopY(ship, t);
            double hullBottomY = computeHullBottomY(ship, t);

            // Player is inside the hull volume if their feet are between
            // hullBottomY and deckTopY (exclusive of top surface so normal
            // deck-snap can handle exact-top-surface cases).
            if (feetY >= hullBottomY && feetY < deckTopY - 0.05D) {
                if (isPlayerInFootprint(px, pz, ship, t)) {
                    return hullBottomY;
                }
            }
        }
        return Double.NaN;
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the world-space Y of the top face of the main deck layer
     * (most-populated integer Y across all blocks + 1.0).
     */
    public static double computeDeckTopY(ShipTransformCache.ClientShip ship, ShipTransform t) {
        java.util.HashMap<Integer, Integer> layerCount = new java.util.HashMap<>();
        for (ShipCrateService.PackedBlock pb : ship.blocks) {
            int iy = (int) Math.round(pb.localOffset().y);
            layerCount.merge(iy, 1, Integer::sum);
        }
        int deckLocalY = 0, best = -1;
        for (java.util.Map.Entry<Integer, Integer> e : layerCount.entrySet()) {
            if (e.getValue() > best) { best = e.getValue(); deckLocalY = e.getKey(); }
        }
        return t.worldOffset().y + deckLocalY + 1.0D;
    }

    /**
     * Returns the world-space Y of the bottom face of the hull
     * (minimum localOffset.y across all blocks).
     */
    private static double computeHullBottomY(ShipTransformCache.ClientShip ship, ShipTransform t) {
        double minLocalY = Double.POSITIVE_INFINITY;
        for (ShipCrateService.PackedBlock pb : ship.blocks) {
            double ly = pb.localOffset().y;
            if (ly < minLocalY) minLocalY = ly;
        }
        return t.worldOffset().y + minLocalY;
    }

    /**
     * Checks if the world XZ point (px, pz) is within the ship's hull
     * footprint using a local-space AABB test correct at any yaw.
     *
     *  1. Translate player XZ relative to ship worldOffset.
     *  2. Inverse-rotate by ship yaw (+yaw, since renderer uses -yaw).
     *  3. Test against local AABB of all block offsets + HULL_MARGIN.
     */
    public static boolean isPlayerInFootprint(double px, double pz,
            ShipTransformCache.ClientShip ship, ShipTransform t) {

        double dx = px - t.worldOffset().x;
        double dz = pz - t.worldOffset().z;

        double rad = Math.toRadians(t.yaw());
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double localX = dx * cos - dz * sin;
        double localZ = dx * sin + dz * cos;

        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (ShipCrateService.PackedBlock pb : ship.blocks) {
            Vec3d lo = pb.localOffset();
            double bx = lo.x - 0.5D;
            double bz = lo.z - 0.5D;
            if (bx        < minX) minX = bx;
            if (bx + 1.0D > maxX) maxX = bx + 1.0D;
            if (bz        < minZ) minZ = bz;
            if (bz + 1.0D > maxZ) maxZ = bz + 1.0D;
        }

        return localX >= minX - HULL_MARGIN && localX <= maxX + HULL_MARGIN
            && localZ >= minZ - HULL_MARGIN && localZ <= maxZ + HULL_MARGIN;
    }

    // Keep old name as alias so ShipPassengerTracker still compiles
    public static boolean isPlayerAboveShipLocal(double px, double pz,
            ShipTransformCache.ClientShip ship, ShipTransform t) {
        return isPlayerInFootprint(px, pz, ship, t);
    }
}
