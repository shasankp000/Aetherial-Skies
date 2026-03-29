package net.shasankp000.Ship.Client;

import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.Network.ShipDeployS2CPacket;
import net.shasankp000.Ship.ShipCrateService;
import net.shasankp000.Ship.Transform.ShipTransform;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side mirror of all active ships.
 *
 * Populated by ShipDeployS2CPacket (hull data once on deploy) and
 * updated every tick by ShipTransformSyncS2CPacket (transform only).
 *
 * The renderer (Part 3) reads directly from this cache.
 * Thread-safe: packet handlers run on the Netty thread, renderer on the
 * main render thread — ConcurrentHashMap prevents data races.
 */
public final class ShipTransformCache {

    public static final ShipTransformCache INSTANCE = new ShipTransformCache();

    private ShipTransformCache() {}

    /** Full snapshot of one client-side ship. */
    public static class ClientShip {
        public final UUID shipId;
        public final List<ShipCrateService.PackedBlock> blocks;
        public volatile ShipTransform transform;

        public ClientShip(
                UUID shipId,
                List<ShipCrateService.PackedBlock> blocks,
                ShipTransform transform
        ) {
            this.shipId = shipId;
            this.blocks = Collections.unmodifiableList(blocks);
            this.transform = transform;
        }
    }

    private final ConcurrentHashMap<UUID, ClientShip> ships = new ConcurrentHashMap<>();

    // ---- Called by packet handlers (any thread) --------------------------

    public void onDeploy(ShipDeployS2CPacket.Payload p) {
        ShipTransform t = new ShipTransform(
            p.structureOrigin(), p.worldOffset(), p.yaw(), Vec3d.ZERO);
        ships.put(p.shipId(), new ClientShip(p.shipId(), p.blocks(), t));
    }

    public void onTransformSync(UUID shipId, Vec3d worldOffset, float yaw, Vec3d velocity) {
        ClientShip ship = ships.get(shipId);
        if (ship != null) {
            ship.transform = ship.transform.withMotion(worldOffset, yaw, velocity);
        }
    }

    public void onRemove(UUID shipId) {
        ships.remove(shipId);
    }

    /** Called on client disconnect / world unload to wipe stale state. */
    public void clear() {
        ships.clear();
    }

    // ---- Called by renderer (render thread) ------------------------------

    public Collection<ClientShip> getAll() {
        return ships.values();
    }

    public ClientShip get(UUID shipId) {
        return ships.get(shipId);
    }
}
