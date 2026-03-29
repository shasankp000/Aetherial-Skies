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
 * Responsibilities:
 *  - Detect if a player's XZ feet position is within the ship's deck footprint.
 *  - If yes, add the ship's XZ velocity to the player so they ride with it.
 *
 * What this does NOT do:
 *  - No requestTeleport / Y snapping. The client-side ShipCollisionMixin
 *    (ChunkCache mixin) already makes ship blocks solid, so vanilla collision
 *    keeps the player on the deck surface naturally. Calling requestTeleport
 *    every tick resets the client's velocity to zero and causes the slowness.
 */
public final class ShipPassengerTracker {

    private ShipPassengerTracker() {}

    private static final double ABOVE_TOLERANCE  = 4.0D;
    private static final double BELOW_TOLERANCE  = 1.5D;
    private static final double FOOTPRINT_MARGIN = 0.3D;

    public static void tick(ShipStructure structure, Iterable<ServerPlayerEntity> players) {
        ShipTransform  t      = structure.getTransform();
        ShipHullData   hull   = structure.getHullData();
        ShipHullData.HullBounds bounds = hull.computeBounds();

        double deckTopY = computeDeckTopY(hull, t);

        double cx    = t.worldOffset().x;
        double cz    = t.worldOffset().z;
        double halfX = bounds.widthX() * 0.5D + FOOTPRINT_MARGIN;
        double halfZ = bounds.widthZ() * 0.5D + FOOTPRINT_MARGIN;

        double footMinX = cx - halfX;
        double footMaxX = cx + halfX;
        double footMinZ = cz - halfZ;
        double footMaxZ = cz + halfZ;
        double footMinY = deckTopY - BELOW_TOLERANCE;
        double footMaxY = deckTopY + ABOVE_TOLERANCE;

        Vec3d shipVel = t.velocity();
        boolean shipMoving = shipVel.horizontalLengthSquared() > 1e-10;

        for (ServerPlayerEntity player : players) {
            if (!isEligible(player)) continue;

            Vec3d feet = player.getPos();

            if (feet.x < footMinX || feet.x > footMaxX
             || feet.z < footMinZ || feet.z > footMaxZ
             || feet.y < footMinY || feet.y > footMaxY) {
                structure.removeBoardedPlayer(player.getUuid());
                continue;
            }

            structure.addBoardedPlayer(player.getUuid());

            // Carry player with ship's horizontal motion only.
            // addVelocity blends with the player's own input velocity
            // and does NOT reset the client's movement state.
            if (shipMoving) {
                player.addVelocity(shipVel.x, 0.0, shipVel.z);
                player.velocityModified = true;
            }
        }
    }

    /**
     * Returns the world-space Y of the top surface of the main deck layer.
     * Finds the most-populated integer localOffset.y across all blocks
     * (ignoring tall structures like the helm) and adds 1.0 for block top.
     */
    private static double computeDeckTopY(ShipHullData hull, ShipTransform t) {
        java.util.HashMap<Integer, Integer> layerCount = new java.util.HashMap<>();
        for (ShipCrateService.PackedBlock pb : hull.blocks()) {
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

    private static boolean isEligible(ServerPlayerEntity player) {
        if (player.isSpectator()) return false;
        if (player.isCreative() && player.getAbilities().flying) return false;
        if (player.hasVehicle()) return false;
        return true;
    }
}
