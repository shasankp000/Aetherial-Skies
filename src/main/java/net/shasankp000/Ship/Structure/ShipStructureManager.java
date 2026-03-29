package net.shasankp000.Ship.Structure;

import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.Dimension.ShipStorageDimension;
import net.shasankp000.Ship.ShipCrateService;
import net.shasankp000.Ship.ShipHullData;
import net.shasankp000.Ship.Transform.ShipTransform;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server singleton that owns every active ShipStructure.
 *
 * Lifecycle:
 *  1. init(server)        — called on SERVER_STARTED
 *  2. deploy(...)         — called when a ShipCrateItem is used
 *  3. destroy(shipId)     — called when a ship is scrapped / removed
 *
 * Persistence (save/load) is handled separately by ShipPersistentState
 * (Part 2+). This class exposes restoreFromSave() for that purpose.
 */
public final class ShipStructureManager {

    private static final ShipStructureManager INSTANCE = new ShipStructureManager();
    private final Map<UUID, ShipStructure> ships = new ConcurrentHashMap<>();
    private MinecraftServer server;

    private ShipStructureManager() {}

    public static ShipStructureManager getInstance() {
        return INSTANCE;
    }

    /** Must be called once on SERVER_STARTED before any deploy() call. */
    public void init(MinecraftServer server) {
        this.server = server;
    }

    // ---- Deploy ----------------------------------------------------------

    /**
     * Deploys a new ship:
     * 1. Allocates a storage slot.
     * 2. Places the hull's real blocks in the ship_storage dimension.
     * 3. Computes the initial ShipTransform so the ship's visual centre
     *    lands at worldPos with the given yaw.
     * 4. Registers the ShipStructure.
     *
     * @param hullData   hull data from the crate item
     * @param worldPos   desired overworld position for the ship's visual centre
     *                   (typically the water-surface position from the old deploy service)
     * @param initialYaw initial facing yaw in degrees
     * @return the newly created ShipStructure
     */
    public ShipStructure deploy(ShipHullData hullData, Vec3d worldPos, float initialYaw) {
        UUID shipId = hullData.shipId();

        ServerWorld storageWorld = getStorageWorld();
        if (storageWorld == null) {
            throw new IllegalStateException(
                "ship_storage dimension not loaded — ensure it is registered in fabric.mod.json and worldgen data packs.");
        }

        BlockPos slotOrigin = ShipSlotAllocator.allocate(shipId);

        // Place real blocks in the storage dimension.
        for (ShipCrateService.PackedBlock pb : hullData.blocks()) {
            BlockPos storagePos = ShipSlotAllocator.localToStorage(shipId, pb.localOffset());
            BlockState state = Registries.BLOCK
                .get(new Identifier(pb.blockId()))
                .getDefaultState();
            storageWorld.setBlockState(storagePos, state);
        }

        // structureOrigin is the centre of the bounding box in storage-dim coords.
        ShipHullData.HullBounds b = hullData.computeBounds();
        Vec3d structureOrigin = new Vec3d(
            slotOrigin.getX() + (b.minX() + b.maxX()) * 0.5,
            slotOrigin.getY() + (b.minY() + b.maxY()) * 0.5,
            slotOrigin.getZ() + (b.minZ() + b.maxZ()) * 0.5
        );

        ShipTransform initialTransform = new ShipTransform(
            structureOrigin,
            worldPos,
            initialYaw,
            Vec3d.ZERO
        );

        ShipStructure structure = new ShipStructure(shipId, hullData, slotOrigin, initialTransform);
        ships.put(shipId, structure);
        return structure;
    }

    // ---- Destroy ---------------------------------------------------------

    /**
     * Removes a ship: clears its blocks from the storage dimension,
     * releases its slot, and deregisters it.
     */
    public void destroy(UUID shipId) {
        ShipStructure structure = ships.remove(shipId);
        if (structure == null) return;

        ServerWorld storageWorld = getStorageWorld();
        if (storageWorld != null) {
            for (BlockPos pos : structure.getStoragePositions()) {
                storageWorld.setBlockState(pos,
                    net.minecraft.block.Blocks.AIR.getDefaultState());
            }
        }
        ShipSlotAllocator.release(shipId);
    }

    // ---- Queries ---------------------------------------------------------

    public ShipStructure getShip(UUID shipId) {
        return ships.get(shipId);
    }

    public Collection<ShipStructure> getAllShips() {
        return Collections.unmodifiableCollection(ships.values());
    }

    /**
     * Finds the ship (if any) whose worldOffset is within {@code range} blocks
     * of the given position. Used by boarding logic.
     */
    public ShipStructure findNearest(Vec3d pos, double range) {
        ShipStructure nearest = null;
        double nearest2 = range * range;
        for (ShipStructure s : ships.values()) {
            double d2 = s.getTransform().worldOffset().squaredDistanceTo(pos);
            if (d2 < nearest2) {
                nearest2 = d2;
                nearest = s;
            }
        }
        return nearest;
    }

    /**
     * Called by ShipPersistentState on world load to restore ships without
     * re-placing blocks (they are already in the storage dim).
     */
    public void restoreFromSave(
            UUID shipId,
            ShipHullData hullData,
            BlockPos slotOrigin,
            ShipTransform transform
    ) {
        ShipSlotAllocator.restore(shipId, slotOrigin);
        ShipStructure structure = new ShipStructure(shipId, hullData, slotOrigin, transform);
        ships.put(shipId, structure);
    }

    // ---- Internal --------------------------------------------------------

    private ServerWorld getStorageWorld() {
        return server == null ? null : server.getWorld(ShipStorageDimension.DIMENSION_KEY);
    }
}
