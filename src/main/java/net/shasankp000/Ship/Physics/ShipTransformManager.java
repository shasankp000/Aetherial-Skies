package net.shasankp000.Ship.Physics;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.shasankp000.Ship.Network.ShipTransformSyncS2CPacket;
import net.shasankp000.Ship.Structure.ShipStructure;
import net.shasankp000.Ship.Structure.ShipStructureManager;
import net.shasankp000.Ship.Transform.ShipTransform;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Drives the physics tick loop for every active ShipStructure.
 *
 * One ShipPhysicsEngine per ship is maintained here. Each server tick:
 *  1. syncFrom(structure)   — read current transform into engine state
 *  2. engine.tick()         — apply gravity / buoyancy / drag
 *  3. applyTo(structure)    — write result back to structure's transform
 *  4. broadcast sync packet — inform all online players of the new transform
 *
 * Uses the overworld as the physics world (fluid checks, terrain collision).
 * Ships are stored in ship_storage but their visual position is in the overworld.
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

    // ---- Called when a ship is deployed (ShipStructureManager.deploy) ----

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

    // ---- Called when a ship is destroyed ---------------------------------

    public void onShipDestroyed(UUID shipId) {
        engines.remove(shipId);
    }

    // ---- Server tick (called from AetherialSkies.onInitialize) -----------

    public void tick() {
        if (server == null) return;
        ServerWorld overworld = server.getOverworld();

        for (ShipStructure structure : ShipStructureManager.getInstance().getAllShips()) {
            if (!structure.isPhysicsActive()) continue;

            ShipPhysicsEngine engine = engines.get(structure.getShipId());
            if (engine == null) {
                // Engine missing — lazily create (handles world-load restore).
                onShipDeployed(structure);
                engine = engines.get(structure.getShipId());
            }

            engine.syncFrom(structure);
            engine.tick();
            engine.applyTo(structure);

            // Broadcast updated transform to all players.
            ShipTransform t = structure.getTransform();
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
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
