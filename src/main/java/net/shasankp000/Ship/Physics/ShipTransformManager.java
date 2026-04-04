package net.shasankp000.Ship.Physics;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.Physics.JoltPhysicsSystem;
import net.shasankp000.Ship.Network.ShipSteerC2SPacket;
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
 * Tick order per frame:
 *  1. Per-ship: syncFrom -> engine.tick() -> applyTo
 *  2. JoltPhysicsSystem.stepSimulation()
 *  3. Per-ship: ShipPassengerTracker.tick(), then broadcast sync packet.
 */
public final class ShipTransformManager {

    private static final ShipTransformManager INSTANCE = new ShipTransformManager();
    private final Map<UUID, ShipPhysicsEngine> engines = new HashMap<>();
    private MinecraftServer server;

    private ShipTransformManager() {}

    public static ShipTransformManager getInstance() { return INSTANCE; }

    public void init(MinecraftServer server) {
        this.server = server;
        // Register the C2S steer packet handler on the server side.
        ServerPlayNetworking.registerGlobalReceiver(
            ShipSteerC2SPacket.ID,
            (srv, player, handler, buf, responseSender) -> {
                ShipSteerC2SPacket.Payload p = ShipSteerC2SPacket.decode(buf);
                srv.execute(() -> ShipPilotManager.getInstance().applySteer(player, p.forward(), p.turn()));
            }
        );
    }

    // ---- Ship lifecycle --------------------------------------------------

    public void onShipDeployed(ShipStructure structure) {
        UUID shipId = structure.getShipId();

        ShipPhysicsState state = new ShipPhysicsState(
            structure.getTransform().worldOffset(),
            structure.getHullData().mass()
        );
        state.yaw = structure.getTransform().yaw();

        ShipPhysicsEngine engine = new ShipPhysicsEngine(
            shipId, state, server.getOverworld());
        engine.setHullData(structure.getHullData());
        engine.setHullBounds(structure.getHullData().computeBounds());
        engines.put(shipId, engine);

        JoltPhysicsSystem.getInstance().registerShipBody(
            shipId,
            structure.getHullData(),
            structure.getTransform().worldOffset(),
            structure.getTransform().yaw()
        );
    }

    public void onShipDestroyed(UUID shipId) {
        engines.remove(shipId);
        JoltPhysicsSystem.getInstance().removeShipBody(shipId);
    }

    // ---- Tick ------------------------------------------------------------

    public void tick() {
        if (server == null) return;

        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();

        // Phase 1: inject steer input, then PD tick.
        for (ShipStructure structure : ShipStructureManager.getInstance().getAllShips()) {
            if (!structure.isPhysicsActive()) continue;

            ShipPhysicsEngine engine = engines.get(structure.getShipId());
            if (engine == null) {
                onShipDeployed(structure);
                engine = engines.get(structure.getShipId());
            }

            // Feed this tick's steer input into the engine.
            ShipSteerInput steer = ShipPilotManager.getInstance().getSteerInput(structure.getShipId());
            engine.setSteerInput(steer);

            engine.syncFrom(structure);
            engine.tick();
            engine.applyTo(structure);
        }

        // Phase 2: step Jolt.
        JoltPhysicsSystem.getInstance().stepSimulation();

        // Phase 3: passengers + broadcast.
        for (ShipStructure structure : ShipStructureManager.getInstance().getAllShips()) {
            if (!structure.isPhysicsActive()) continue;

            ShipPhysicsEngine engine = engines.get(structure.getShipId());
            if (engine == null) continue;

            ShipPassengerTracker.tick(structure, players);

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
