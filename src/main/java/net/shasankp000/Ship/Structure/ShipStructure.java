package net.shasankp000.Ship.Structure;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.ShipCrateService;
import net.shasankp000.Ship.ShipHullData;
import net.shasankp000.Ship.Transform.ShipTransform;

import java.util.*;

/**
 * Server-side representation of one deployed ship.
 *
 * Blocks live at fixed positions in the ship_storage dimension (never moved
 * during sailing). The ShipTransform controls where they appear to be in the
 * overworld at render time.
 *
 * HullBounds and deckLocalY are computed once at construction time from the
 * immutable ShipHullData and cached here so that per-tick code (physics
 * engine, passenger tracker, Jolt) never needs to iterate the block list.
 */
public class ShipStructure {

    private final UUID shipId;
    private final ShipHullData hullData;
    private final BlockPos slotOrigin;
    private ShipTransform transform;
    private boolean physicsActive = true;

    // Precomputed from hull (immutable after construction).
    private final ShipHullData.HullBounds cachedBounds;
    private final int cachedDeckLocalY;

    private final Set<UUID> boardedPlayers = new HashSet<>();

    public ShipStructure(
            UUID shipId,
            ShipHullData hullData,
            BlockPos slotOrigin,
            ShipTransform initialTransform
    ) {
        this.shipId    = shipId;
        this.hullData  = hullData;
        this.slotOrigin = slotOrigin;
        this.transform = initialTransform;

        this.cachedBounds    = hullData.computeBounds();
        this.cachedDeckLocalY = computeDeckLocalY(hullData);
    }

    // ---- Static helpers --------------------------------------------------

    /**
     * Find the most-populated integer Y layer across all hull blocks.
     * This is the deck layer (the broadest flat layer of the hull).
     */
    private static int computeDeckLocalY(ShipHullData hull) {
        Map<Integer, Integer> layerCount = new HashMap<>();
        for (ShipCrateService.PackedBlock pb : hull.blocks()) {
            int iy = (int) Math.round(pb.localOffset().y);
            layerCount.merge(iy, 1, Integer::sum);
        }
        int deckLocalY = 0;
        int best = -1;
        for (Map.Entry<Integer, Integer> e : layerCount.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                deckLocalY = e.getKey();
            }
        }
        return deckLocalY;
    }

    // ---- Accessors -------------------------------------------------------

    public UUID getShipId()              { return shipId; }
    public ShipHullData getHullData()    { return hullData; }
    public BlockPos getSlotOrigin()      { return slotOrigin; }
    public ShipTransform getTransform()  { return transform; }
    public void setTransform(ShipTransform t) { this.transform = t; }
    public boolean isPhysicsActive()     { return physicsActive; }
    public void setPhysicsActive(boolean v)  { physicsActive = v; }

    /** Pre-computed hull AABB — never iterate hullData.blocks() for bounds. */
    public ShipHullData.HullBounds getCachedBounds() { return cachedBounds; }

    /**
     * World-space Y of the top surface of the deck, derived from the
     * current transform and the pre-computed deck layer offset.
     */
    public double getDeckTopY() {
        return transform.worldOffset().y + cachedDeckLocalY + 1.0D;
    }

    // ---- Boarding --------------------------------------------------------

    public void addBoardedPlayer(UUID id)    { boardedPlayers.add(id); }
    public void removeBoardedPlayer(UUID id) { boardedPlayers.remove(id); }
    public Set<UUID> getBoardedPlayers()     { return Collections.unmodifiableSet(boardedPlayers); }

    // ---- Derived helpers -------------------------------------------------

    /**
     * World-space position a player should be teleported to when boarding
     * at the helm. Applies the current transform to the hull's helm offset.
     */
    public Vec3d getHelmWorldPos() {
        Vec3d helm = hullData.helmOffset();
        return ShipTransform.localToWorld(transform, helm).add(0, 0.7, 0);
    }

    /**
     * All block positions belonging to this structure in the ship_storage
     * dimension. Order matches hullData.blocks().
     */
    public List<BlockPos> getStoragePositions() {
        List<BlockPos> result = new ArrayList<>(hullData.blocks().size());
        for (ShipCrateService.PackedBlock pb : hullData.blocks()) {
            result.add(ShipSlotAllocator.localToStorage(shipId, pb.localOffset()));
        }
        return result;
    }
}
