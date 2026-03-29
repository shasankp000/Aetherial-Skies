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
 *  - Y-snap: if the player is within snap range of the deck top, set their
 *    position flush on the deck so they never fall through.
 *  - Carry player horizontally with the ship's XZ velocity.
 *
 * Why Y-snap instead of relying on ChunkCache ghost blocks:
 *  The ChunkCache mixin only fires for block queries initiated by the client's
 *  own collision engine. When the ship entity moves, the player's position
 *  relative to the ship changes each tick, and Minecraft's ChunkCache window
 *  may not include the ship's rendered block positions (they are not real blocks).
 *  The result is the player falls through. The VS2 solution (EntityDragger) is
 *  to skip the block-collision pipeline entirely and directly set the player's
 *  Y position before vanilla physics runs. We do the same here.
 */
public final class ShipPassengerTracker {

    private ShipPassengerTracker() {}

    private static final double ABOVE_TOLERANCE  = 4.0D;
    private static final double BELOW_TOLERANCE  = 1.5D;
    private static final double FOOTPRINT_MARGIN = 0.3D;

    /** How far below the deck top we still snap up (catches one-tick fall-through). */
    private static final double SNAP_DOWN_TOLERANCE = 1.1D;
    /** How far above the deck top we still snap down (prevents snapping mid-jump). */
    private static final double SNAP_UP_TOLERANCE   = 0.55D;

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

            // --- Y-snap: keep player on deck surface (VS2-style) ---
            // Only snap when the player is falling or stationary, not jumping.
            boolean falling = player.getVelocity().y <= 0.001D;
            if (falling) {
                double feetY = feet.y;
                if (feetY < deckTopY) {
                    // Fell through: push back up
                    player.setPosition(feet.x, deckTopY, feet.z);
                    Vec3d vel = player.getVelocity();
                    player.setVelocity(vel.x, 0.0D, vel.z);
                    player.setOnGround(true);
                } else if (feetY <= deckTopY + SNAP_UP_TOLERANCE) {
                    // Resting on or fractionally above deck: snap flush
                    player.setPosition(feet.x, deckTopY, feet.z);
                    Vec3d vel = player.getVelocity();
                    if (vel.y < 0) player.setVelocity(vel.x, 0.0D, vel.z);
                    player.setOnGround(true);
                }
            }

            // Carry player with ship's horizontal motion.
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
