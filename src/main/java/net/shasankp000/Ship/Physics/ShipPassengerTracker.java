package net.shasankp000.Ship.Physics;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.ShipHullData;
import net.shasankp000.Ship.Structure.ShipStructure;
import net.shasankp000.Ship.Transform.ShipTransform;

/**
 * Moves players that are standing on a ship along with it each tick.
 *
 * All expensive per-block iteration (bounds, deckLocalY) is now done once
 * at ship construction time and cached in ShipStructure.
 */
public final class ShipPassengerTracker {

    private ShipPassengerTracker() {}

    private static final double ABOVE_TOLERANCE  = 4.0D;
    private static final double BELOW_TOLERANCE  = 1.5D;
    private static final double FOOTPRINT_MARGIN = 0.3D;
    private static final double SNAP_DOWN_TOLERANCE = 1.1D;
    private static final double SNAP_UP_TOLERANCE   = 0.55D;

    public static void tick(ShipStructure structure, Iterable<ServerPlayerEntity> players) {
        ShipTransform t = structure.getTransform();

        // Use pre-computed cached values — no block iteration here.
        ShipHullData.HullBounds bounds = structure.getCachedBounds();
        double deckTopY = structure.getDeckTopY();

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

        Vec3d shipVel   = t.velocity();
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

            boolean falling = player.getVelocity().y <= 0.001D;
            if (falling) {
                double feetY = feet.y;
                if (feetY < deckTopY) {
                    player.setPosition(feet.x, deckTopY, feet.z);
                    Vec3d vel = player.getVelocity();
                    player.setVelocity(vel.x, 0.0D, vel.z);
                    player.setOnGround(true);
                } else if (feetY <= deckTopY + SNAP_UP_TOLERANCE) {
                    player.setPosition(feet.x, deckTopY, feet.z);
                    Vec3d vel = player.getVelocity();
                    if (vel.y < 0) player.setVelocity(vel.x, 0.0D, vel.z);
                    player.setOnGround(true);
                }
            }

            if (shipMoving) {
                player.addVelocity(shipVel.x, 0.0, shipVel.z);
                player.velocityModified = true;
            }
        }
    }

    private static boolean isEligible(ServerPlayerEntity player) {
        if (player.isSpectator()) return false;
        if (player.isCreative() && player.getAbilities().flying) return false;
        if (player.hasVehicle()) return false;
        return true;
    }
}
