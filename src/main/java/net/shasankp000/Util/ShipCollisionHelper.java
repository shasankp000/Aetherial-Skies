package net.shasankp000.Util;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import net.shasankp000.Entity.GravityBlockEntity;

import java.util.List;

/**
 * Utility methods for resolving player/ship-deck collision.
 *
 * The core idea (borrowed from VS2's EntityDragger) is:
 *   - We do NOT touch Minecraft's block-collision pipeline at all.
 *   - Instead, every tick we find any GravityBlockEntity whose top face
 *     the player is on or has just passed through, and we correct the
 *     player's Y so they sit flush on top.
 *
 * This avoids the ChunkCache ghost-block problem entirely: the entity IS
 * real, so we just query the world entity list.
 */
public final class ShipCollisionHelper {

    private ShipCollisionHelper() {}

    /**
     * Vertical tolerance: how far below the top face we still consider
     * the player to be "on" the block (catches one-tick fall-through).
     */
    public static final double SNAP_TOLERANCE = 1.1;

    /**
     * How far above the top face the player's feet must be to trigger a
     * downward-snap (prevents snapping when jumping off).
     */
    public static final double MAX_ABOVE = 0.55;

    /**
     * Finds the highest GravityBlockEntity top-face Y that the player's
     * AABB overlaps horizontally AND whose top face is within SNAP_TOLERANCE
     * below the player's feet.
     *
     * Returns Double.NaN if no such block exists.
     */
    public static double findFloorY(PlayerEntity player, World world) {
        Box playerBox = player.getBoundingBox();

        // Search box: horizontally the player's AABB, vertically from
        // SNAP_TOLERANCE below feet up to MAX_ABOVE above feet.
        Box searchBox = new Box(
                playerBox.minX, playerBox.minY - SNAP_TOLERANCE, playerBox.minZ,
                playerBox.maxX, playerBox.minY + MAX_ABOVE,   playerBox.maxZ
        );

        List<GravityBlockEntity> nearby = world.getEntitiesByClass(
                GravityBlockEntity.class,
                searchBox,
                e -> !e.isRemoved()
        );

        double bestTopY = Double.NaN;
        for (GravityBlockEntity ship : nearby) {
            Box shipBox = ship.getBoundingBox();

            // Must overlap horizontally with the player
            if (shipBox.maxX <= playerBox.minX || shipBox.minX >= playerBox.maxX) continue;
            if (shipBox.maxZ <= playerBox.minZ || shipBox.minZ >= playerBox.maxZ) continue;

            double topY = shipBox.maxY;

            // Top face must be within the snap window relative to player feet
            double feetY = playerBox.minY;
            if (topY > feetY + MAX_ABOVE) continue;   // ship is above player (jumping)
            if (topY < feetY - SNAP_TOLERANCE) continue; // ship is too far below

            if (Double.isNaN(bestTopY) || topY > bestTopY) {
                bestTopY = topY;
            }
        }

        return bestTopY;
    }

    /**
     * Returns true if the player is falling or neutral (not actively jumping).
     * We only snap downward onto the deck when the player is not going upward.
     */
    public static boolean isFallingOrNeutral(PlayerEntity player) {
        return player.getVelocity().y <= 0.001;
    }
}
