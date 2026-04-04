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

    // How far (in blocks) a helm block can be from a ship's world-offset
    // and still be considered "belonging" to that ship.
    private static final double HELM_SNAP_RANGE = 64.0;

    /** Map: shipId -> piloting player UUID */
    private final Map<UUID, UUID> shipToPilot = new ConcurrentHashMap<>();

    /** Map: player UUID -> shipId (reverse lookup) */
    private final Map<UUID, UUID> pilotToShip = new ConcurrentHashMap<>();

    /** Map: shipId -> current steer input (updated each tick from C2S packet) */
    private final Map<UUID, ShipSteerInput> steerInputs = new ConcurrentHashMap<>();

    // ---- Helpers ---------------------------------------------------------

    /**
     * Find the ship whose world-offset is nearest to the centre of the given
     * BlockPos, within HELM_SNAP_RANGE blocks.  This is a stand-in for a
     * true "block belongs to ship" lookup that we can add later once the
     * storage → world projection is queryable.
     */
    private ShipStructure findShipForHelm(BlockPos helmPos) {
        Vec3d centre = Vec3d.ofCenter(helmPos);
        return ShipStructureManager.getInstance().findNearest(centre, HELM_SNAP_RANGE);
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
        if (ship == null) {
            AetherialSkies.LOGGER.warn("[ShipPilotManager] No ship found near helm at {}", helmPos);
            return false;
        }

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
