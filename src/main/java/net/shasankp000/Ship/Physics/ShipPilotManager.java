package net.shasankp000.Ship.Physics;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.AetherialSkies;
import net.shasankp000.Ship.Network.ShipPilotEnteredS2CPacket;
import net.shasankp000.Ship.Network.ShipPilotExitedS2CPacket;
import net.shasankp000.Ship.Structure.ShipStructure;
import net.shasankp000.Ship.Structure.ShipStructureManager;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side registry of all active piloting sessions.
 *
 * Each ship can have at most one active pilot at a time.
 * The pilot's steer input is stored here and consumed by
 * ShipTransformManager each tick.
 */
public final class ShipPilotManager {

    private static final ShipPilotManager INSTANCE = new ShipPilotManager();
    private ShipPilotManager() {}
    public static ShipPilotManager getInstance() { return INSTANCE; }

    /**
     * Maximum distance (blocks) between the clicked BlockPos centre and a
     * ship's projected helm world-position for the helm to be considered a
     * match.  3 blocks is generous enough to account for sub-block offsets
     * while still being unambiguous when ships are near each other.
     */
    private static final double HELM_MATCH_RADIUS = 3.0;

    /** Map: shipId -> piloting player UUID */
    private final Map<UUID, UUID> shipToPilot = new ConcurrentHashMap<>();

    /** Map: player UUID -> shipId (reverse lookup) */
    private final Map<UUID, UUID> pilotToShip = new ConcurrentHashMap<>();

    /** Map: shipId -> current steer input (updated each tick from C2S packet) */
    private final Map<UUID, ShipSteerInput> steerInputs = new ConcurrentHashMap<>();

    // ---- Helpers ---------------------------------------------------------

    /**
     * Find the ship whose projected helm world-position is closest to the
     * centre of {@code helmPos}, within {@link #HELM_MATCH_RADIUS} blocks.
     *
     * <p>This is correct because {@link net.shasankp000.Ship.Structure.ShipStructure#getHelmWorldPos()}
     * returns the helm's overworld position as computed from the ship's
     * current transform — exactly the position the player right-clicked.
     */
    private ShipStructure findShipForHelm(BlockPos helmPos) {
        Vec3d clicked = Vec3d.ofCenter(helmPos);
        double bestDist2 = HELM_MATCH_RADIUS * HELM_MATCH_RADIUS;
        ShipStructure best = null;

        for (ShipStructure ship : ShipStructureManager.getInstance().getAllShips()) {
            double d2 = ship.getHelmWorldPos().squaredDistanceTo(clicked);
            if (d2 < bestDist2) {
                bestDist2 = d2;
                best = ship;
            }
        }

        if (best == null) {
            AetherialSkies.LOGGER.warn(
                "[ShipPilotManager] No ship helm found near block {} (search radius={} blocks)",
                helmPos, HELM_MATCH_RADIUS);
        }
        return best;
    }

    // ---- Entry / exit ----------------------------------------------------

    /**
     * Called when a player right-clicks a helm block.
     * If the player is already piloting this ship, exits instead.
     * Returns true if the player entered pilot mode, false if exited.
     */
    public boolean togglePilot(ServerPlayerEntity player, BlockPos helmPos) {
        UUID playerId = player.getUuid();

        // If already piloting something, exit first.
        if (pilotToShip.containsKey(playerId)) {
            UUID existingShipId = pilotToShip.get(playerId);
            exitPilot(player);
            // If they clicked the helm of the same ship they were piloting, just exit.
            ShipStructure existing = findShipForHelm(helmPos);
            if (existing != null && existing.getShipId().equals(existingShipId)) return false;
        }

        // Find which ship owns this helm block.
        ShipStructure ship = findShipForHelm(helmPos);
        if (ship == null) return false;

        // Check nobody else is already piloting this ship.
        if (shipToPilot.containsKey(ship.getShipId())) {
            AetherialSkies.LOGGER.info("[ShipPilotManager] Ship {} already has a pilot",
                ship.getShipId().toString().substring(0, 8));
            return false;
        }

        UUID shipId = ship.getShipId();
        shipToPilot.put(shipId, playerId);
        pilotToShip.put(playerId, shipId);
        steerInputs.put(shipId, ShipSteerInput.NONE);

        ShipPilotEnteredS2CPacket.send(player, shipId);
        AetherialSkies.LOGGER.info("[ShipPilotManager] {} entered pilot mode for ship {}",
            player.getName().getString(), shipId.toString().substring(0, 8));
        return true;
    }

    public void exitPilot(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        UUID shipId = pilotToShip.remove(playerId);
        if (shipId == null) return;
        shipToPilot.remove(shipId);
        steerInputs.remove(shipId);
        ShipPilotExitedS2CPacket.send(player);
        AetherialSkies.LOGGER.info("[ShipPilotManager] {} exited pilot mode",
            player.getName().getString());
    }

    // ---- Steer input (written from C2S packet handler) -------------------

    public void applySteer(ServerPlayerEntity player, int forward, int turn) {
        UUID shipId = pilotToShip.get(player.getUuid());
        if (shipId == null) return;
        steerInputs.put(shipId, new ShipSteerInput(forward, turn));
    }

    // ---- Consumed by ShipTransformManager each tick ----------------------

    public ShipSteerInput getSteerInput(UUID shipId) {
        return steerInputs.getOrDefault(shipId, ShipSteerInput.NONE);
    }

    public boolean isPiloted(UUID shipId) {
        return shipToPilot.containsKey(shipId);
    }

    /** Called on player disconnect to clean up any live session. */
    public void onPlayerDisconnect(ServerPlayerEntity player) {
        exitPilot(player);
    }
}
