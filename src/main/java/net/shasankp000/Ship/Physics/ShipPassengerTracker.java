package net.shasankp000.Ship.Physics;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.ShipHullData;
import net.shasankp000.Ship.Structure.ShipStructure;
import net.shasankp000.Ship.Transform.ShipTransform;

/**
 * Determines whether a player is standing on a ship and, if so,
 * moves them with the ship each tick.
 *
 * How it works (Approach B — server-side position tracking):
 *
 *  1. Compute the ship's deck AABB in world space from its hull bounds
 *     and current transform.
 *  2. If the player's feet are inside the deck footprint (XZ) and within
 *     a small vertical window above the deck surface, they are "on deck".
 *  3. Each tick, the ship's XZ displacement (velocity * 1 tick) is added
 *     to the player's position so they ride with the ship.
 *  4. If the ship moves vertically (e.g. settling on water), the player's
 *     Y is snapped to the deck surface so they don't fall through or float.
 *
 * The tracker is stateless — it computes fresh each tick from the ship's
 * current transform and the player's current position.
 */
public final class ShipPassengerTracker {

    private ShipPassengerTracker() {}

    /**
     * How many blocks above the deck top surface a player can still be
     * considered "on deck" (accounts for jumping).
     */
    private static final double DECK_ABOVE_TOLERANCE = 3.0D;

    /**
     * How many blocks below the deck surface a player can be before they
     * are no longer considered on-deck (sinking through tolerance).
     */
    private static final double DECK_BELOW_TOLERANCE = 0.6D;

    /**
     * XZ margin added to the hull footprint when checking if a player is
     * on the ship.  Gives a little forgiveness at edges.
     */
    private static final double FOOTPRINT_MARGIN = 0.1D;

    /**
     * Ticks the passenger logic for one ship.
     * Must be called AFTER the physics engine has updated the ship's transform
     * for this tick, so velocity reflects actual displacement.
     *
     * @param structure  the ship whose passengers to update
     * @param players    all online players to check
     */
    public static void tick(ShipStructure structure, Iterable<ServerPlayerEntity> players) {
        ShipTransform transform = structure.getTransform();
        ShipHullData.HullBounds bounds = structure.getHullData().computeBounds();

        // World-space deck top Y (the surface the player walks on).
        double deckTopY = transform.worldOffset().y + bounds.maxY();

        // Axis-aligned footprint in world XZ (no yaw rotation for simplicity;
        // works well for axis-aligned ships, add rotation later if needed).
        double cx = transform.worldOffset().x;
        double cz = transform.worldOffset().z;
        double halfX = bounds.widthX() * 0.5D + FOOTPRINT_MARGIN;
        double halfZ = bounds.widthZ() * 0.5D + FOOTPRINT_MARGIN;

        Box footprint = new Box(
            cx - halfX, deckTopY - DECK_BELOW_TOLERANCE, cz - halfZ,
            cx + halfX, deckTopY + DECK_ABOVE_TOLERANCE,  cz + halfZ
        );

        Vec3d shipVelocity = transform.velocity();

        for (ServerPlayerEntity player : players) {
            if (!isEligible(player)) continue;

            Vec3d feet = player.getPos();

            if (!footprint.contains(feet)) {
                // Player not on this ship — remove from boarded set if present.
                structure.removeBoardedPlayer(player.getUuid());
                continue;
            }

            // ---- Player is on deck ----------------------------------------
            structure.addBoardedPlayer(player.getUuid());

            // Carry player with the ship's XZ motion.
            double newX = feet.x + shipVelocity.x;
            double newZ = feet.z + shipVelocity.z;

            // Snap Y: if the player is within DECK_BELOW_TOLERANCE below the
            // deck, push them to the deck surface.  If the ship rose this tick
            // (positive shipVelocity.y), rise with it.
            double newY = feet.y;
            if (feet.y < deckTopY) {
                // Player has sunk below deck — snap back to surface.
                newY = deckTopY;
            } else if (shipVelocity.y > 0) {
                // Ship rose this tick — lift player by the same amount.
                newY = feet.y + shipVelocity.y;
            }
            // If ship fell (vy < 0) we let vanilla gravity handle the player
            // naturally rather than yanking them down.

            // Only teleport if we actually moved the player.
            if (Math.abs(newX - feet.x) > 1e-6
                    || Math.abs(newZ - feet.z) > 1e-6
                    || Math.abs(newY - feet.y) > 1e-4) {
                player.requestTeleport(newX, newY, newZ);
            }
        }
    }

    // ---- Helpers ---------------------------------------------------------

    /**
     * Returns false for players that should not be affected:
     * spectators, creative-fly, already riding a vehicle.
     */
    private static boolean isEligible(ServerPlayerEntity player) {
        if (player.isSpectator()) return false;
        if (player.isCreative() && player.getAbilities().flying) return false;
        if (player.hasVehicle()) return false;
        return true;
    }
}
