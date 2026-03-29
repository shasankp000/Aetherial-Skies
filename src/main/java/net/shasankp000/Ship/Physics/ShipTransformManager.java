package net.shasankp000.Ship.Physics;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.shasankp000.Ship.Network.ShipTransformSyncS2CPacket;
import net.shasankp000.Ship.Structure.ShipStructure;
import net.shasankp000.Ship.Structure.ShipStructureManager;
import net.shasankp000.Ship.Transform.ShipTransform;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drives the physics tick loop for every active ShipStructure.
 *
 * Tick order per ship:
 *  1. syncFrom(structure)      — read current transform into engine state
 *  2. engine.tick()            — apply gravity / buoyancy / drag
 *  3. applyTo(structure)       — write result back to structure's transform
 *  4. ShipPassengerTracker     — carry players standing on this ship
 *  5. broadcast sync packet    — inform all online players of new transform
 */
public final class ShipTransformManager {

    private static final ShipTransformManager INSTANCE = new ShipTransformManager();
    private final Map<UUID, ShipPhysicsEngine> engines = new HashMap<>();
    private MinecraftServer server;

    private ShipTransformManager() {}

    public static ShipTransformManager getInstance() { return INSTANCE; }

    public void init(MinecraftServer server) {
        this.server = server;
    }

    public void onShipDeployed(ShipStructure structure) {
        ShipPhysicsState state = new ShipPhysicsState(
            structure.getTransform().worldOffset(),
            structure.getHullData().mass()
        );
        state.yaw = structure.getTransform().yaw();

        ShipPhysicsEngine engine = new ShipPhysicsEngine(state, server.getOverworld());
        engine.setHullData(structure.getHullData());
        engine.setHullBounds(structure.getHullData().computeBounds());
        engines.put(structure.getShipId(), engine);
    }

    public void onShipDestroyed(UUID shipId) {
        engines.remove(shipId);
    }

    public void tick() {
        if (server == null) return;

        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();

        for (ShipStructure structure : ShipStructureManager.getInstance().getAllShips()) {
            if (!structure.isPhysicsActive()) continue;

            ShipPhysicsEngine engine = engines.get(structure.getShipId());
            if (engine == null) {
                onShipDeployed(structure);
                engine = engines.get(structure.getShipId());
            }

            // 1-3: physics
            engine.syncFrom(structure);
            engine.tick();
            engine.applyTo(structure);

            // 4: carry passengers
            ShipPassengerTracker.tick(structure, players);

            // 5: broadcast
            ShipTransform t = structure.getTransform();
            for (ServerPlayerEntity player : players) {
                ShipTransformSyncS2CPacket.send(
                    player,
                    structure.getShipId(),
                    t.worldOffset(),
                    t.yaw(),
                    t.velocity()
                );
            }
        }
    }
}
