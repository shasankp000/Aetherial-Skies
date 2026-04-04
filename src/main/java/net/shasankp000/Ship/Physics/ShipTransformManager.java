package net.shasankp000.Ship.Physics;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.shasankp000.Physics.JoltPhysicsSystem;
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
 *     engine.tick() pushes the PD-computed position into Jolt via
 *     updateBodyTransform() so the broadphase AABB stays current.
 *  2. JoltPhysicsSystem.stepSimulation() -- advance Jolt one tick.
 *  3. Per-ship: ShipPassengerTracker.tick(), then broadcast sync packet.
 *
 * NOTE: we intentionally do NOT read back from Jolt after stepSimulation().
 * Jolt's internal position interpolation produces a slightly different Y
 * than what we set, corrupting the waterline controller. The PD engine
 * position is the authoritative source; Jolt is used only for broadphase
 * and future collision response.
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

        // Phase 1: PD controller tick + push position into Jolt broadphase.
        for (ShipStructure structure : ShipStructureManager.getInstance().getAllShips()) {
            if (!structure.isPhysicsActive()) continue;

            ShipPhysicsEngine engine = engines.get(structure.getShipId());
            if (engine == null) {
                onShipDeployed(structure);
                engine = engines.get(structure.getShipId());
            }

            engine.syncFrom(structure);
            engine.tick();
            engine.applyTo(structure);
        }

        // Phase 2: step Jolt (updates broadphase, runs contact callbacks).
        JoltPhysicsSystem.getInstance().stepSimulation();

        // Phase 3: passengers + broadcast. NO Jolt read-back.
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
