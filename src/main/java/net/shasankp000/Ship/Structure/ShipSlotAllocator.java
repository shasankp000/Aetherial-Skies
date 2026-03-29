package net.shasankp000.Ship.Structure;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.Dimension.ShipStorageDimension;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Assigns non-overlapping rectangular regions ("slots") in the ship_storage
 * dimension, one per ship. Slots are 64 x 64 blocks in XZ, packed in a row
 * along the X axis. A 5x3 corvette easily fits; 64 gives comfortable margin
 * for larger ships in the future.
 */
public final class ShipSlotAllocator {

    public static final int SLOT_SIZE = 64;

    private static final ConcurrentHashMap<UUID, BlockPos> shipToOrigin = new ConcurrentHashMap<>();
    private static final AtomicInteger nextIndex = new AtomicInteger(0);

    private ShipSlotAllocator() {}

    /**
     * Allocates (or retrieves an already-allocated) storage slot for a ship.
     * Returns the slot origin BlockPos in the ship_storage dimension.
     */
    public static BlockPos allocate(UUID shipId) {
        return shipToOrigin.computeIfAbsent(shipId, id -> {
            int index = nextIndex.getAndIncrement();
            int slotX = index * SLOT_SIZE;
            return new BlockPos(slotX, ShipStorageDimension.STORAGE_Y, 0);
        });
    }

    /** Releases the slot for a destroyed ship. */
    public static void release(UUID shipId) {
        shipToOrigin.remove(shipId);
    }

    /** Returns the previously allocated origin, or null if not yet allocated. */
    public static BlockPos getOrigin(UUID shipId) {
        return shipToOrigin.get(shipId);
    }

    /**
     * Converts a block's local offset (relative to the hull's own origin,
     * i.e. from ShipHullData.PackedBlock.localOffset()) to its absolute
     * BlockPos in the ship_storage dimension.
     *
     * The hull origin in local space is (0,0,0). Slot origin in storage space
     * maps to local (0,0,0).
     */
    public static BlockPos localToStorage(UUID shipId, Vec3d localOffset) {
        BlockPos origin = shipToOrigin.get(shipId);
        if (origin == null) {
            throw new IllegalStateException("No slot allocated for ship " + shipId);
        }
        return origin.add(
            (int) Math.round(localOffset.x),
            (int) Math.round(localOffset.y),
            (int) Math.round(localOffset.z)
        );
    }

    /**
     * Restores a previously saved allocation (called during world load from
     * ShipPersistentState). Skips allocation if the ship already has a slot.
     */
    public static void restore(UUID shipId, BlockPos origin) {
        shipToOrigin.putIfAbsent(shipId, origin);
        // Ensure the next fresh allocation lands beyond all restored slots.
        int restoredIndex = origin.getX() / SLOT_SIZE;
        nextIndex.accumulateAndGet(restoredIndex + 1, Math::max);
    }
}
