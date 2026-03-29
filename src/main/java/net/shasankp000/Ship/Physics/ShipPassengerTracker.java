package net.shasankp000.Ship.Physics;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.ShipCrateService;
import net.shasankp000.Ship.ShipHullData;
import net.shasankp000.Ship.Structure.ShipStructure;
import net.shasankp000.Ship.Transform.ShipTransform;

/**
 * Moves players that are standing on a ship along with it each tick.
 *
 * Fix summary vs previous version:
 *  - deckTopY now computed as the top of the LOWEST block layer that makes
 *    up the main deck (most-common localOffset.y + 1), not hull maxY which
 *    was pointing at the helm top.
 *  - Movement uses player.addVelocity() instead of requestTeleport() so
 *    the player’s own input is not zeroed every tick.
 *  - Y correction only fires when the player has actually sunk below the
 *    deck surface (falling through), using a small threshold to avoid
 *    fighting vanilla step-up logic.
 */
public final class ShipPassengerTracker {

    private ShipPassengerTracker() {}

    /** Vertical window above the deck in which a player is “on deck”. */
    private static final double ABOVE_TOLERANCE = 4.0D;
    /** How far below deck we still consider the player “on deck” (step-down). */
    private static final double BELOW_TOLERANCE = 0.5D;
    /** XZ margin added around hull footprint. */
    private static final double FOOTPRINT_MARGIN = 0.3D;

    public static void tick(ShipStructure structure, Iterable<ServerPlayerEntity> players) {
        ShipTransform      t      = structure.getTransform();
        ShipHullData       hull   = structure.getHullData();
        ShipHullData.HullBounds bounds = hull.computeBounds();

        double deckTopY = computeDeckTopY(hull, t);

        // Axis-aligned footprint in world XZ.
        double cx     = t.worldOffset().x;
        double cz     = t.worldOffset().z;
        double halfX  = bounds.widthX() * 0.5D + FOOTPRINT_MARGIN;
        double halfZ  = bounds.widthZ() * 0.5D + FOOTPRINT_MARGIN;

        double footMinX = cx - halfX;
        double footMaxX = cx + halfX;
        double footMinZ = cz - halfZ;
        double footMaxZ = cz + halfZ;
        double footMinY = deckTopY - BELOW_TOLERANCE;
        double footMaxY = deckTopY + ABOVE_TOLERANCE;

        Vec3d shipVel = t.velocity();

        for (ServerPlayerEntity player : players) {
            if (!isEligible(player)) continue;

            Vec3d feet = player.getPos();

            // Check XZ footprint.
            if (feet.x < footMinX || feet.x > footMaxX
             || feet.z < footMinZ || feet.z > footMaxZ
             || feet.y < footMinY || feet.y > footMaxY) {
                structure.removeBoardedPlayer(player.getUuid());
                continue;
            }

            structure.addBoardedPlayer(player.getUuid());

            // --- Carry player with ship XZ movement -----------------------
            // addVelocity accumulates into the player’s own velocity buffer
            // so it blends with their input rather than overriding it.
            if (Math.abs(shipVel.x) > 1e-6 || Math.abs(shipVel.z) > 1e-6) {
                player.addVelocity(shipVel.x, 0.0, shipVel.z);
                player.velocityModified = true;
            }

            // --- Y correction: only if player has sunk below deck ---------
            // The client-side collision mixin handles the normal case.
            // This is a fallback in case they slip through.
            if (feet.y < deckTopY - 0.05D) {
                player.requestTeleport(feet.x, deckTopY, feet.z);
            }
        }
    }

    /**
     * Finds the world-space Y of the top surface of the main deck layer.
     *
     * Strategy: collect all unique localOffset.y values from the hull blocks,
     * find the one with the highest count (the deck layer), add 1.0 (block top),
     * then apply the ship’s worldOffset.y.
     *
     * This correctly ignores tall structures like the helm log that sit on top.
     */
    private static double computeDeckTopY(ShipHullData hull, ShipTransform t) {
        // Count occurrences of each integer Y layer.
        java.util.HashMap<Integer, Integer> layerCount = new java.util.HashMap<>();
        for (ShipCrateService.PackedBlock pb : hull.blocks()) {
            int iy = (int) Math.round(pb.localOffset().y);
            layerCount.merge(iy, 1, Integer::sum);
        }

        // Pick the most-populated layer (the main deck).
        int deckLocalY = 0;
        int best = -1;
        for (java.util.Map.Entry<Integer, Integer> e : layerCount.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                deckLocalY = e.getKey();
            }
        }

        // top surface = localY + 1.0, then offset by world position.
        return t.worldOffset().y + deckLocalY + 1.0D;
    }

    private static boolean isEligible(ServerPlayerEntity player) {
        if (player.isSpectator()) return false;
        if (player.isCreative() && player.getAbilities().flying) return false;
        if (player.hasVehicle()) return false;
        return true;
    }
}
