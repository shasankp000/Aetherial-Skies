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
 */
public class ShipStructure {

    private final UUID shipId;
    private final ShipHullData hullData;
    private final BlockPos slotOrigin;   // origin of this ship's slot in ship_storage
    private ShipTransform transform;      // current world-space transform (mutated each physics tick)
    private boolean physicsActive = true;

    // UUIDs of players currently aboard (server-side)
    private final Set<UUID> boardedPlayers = new HashSet<>();

    public ShipStructure(
            UUID shipId,
            ShipHullData hullData,
            BlockPos slotOrigin,
            ShipTransform initialTransform
    ) {
        this.shipId = shipId;
        this.hullData = hullData;
        this.slotOrigin = slotOrigin;
        this.transform = initialTransform;
    }

    // ---- Accessors -------------------------------------------------------

    public UUID getShipId()              { return shipId; }
    public ShipHullData getHullData()    { return hullData; }
    public BlockPos getSlotOrigin()      { return slotOrigin; }
    public ShipTransform getTransform()  { return transform; }
    public void setTransform(ShipTransform t) { this.transform = t; }
    public boolean isPhysicsActive()     { return physicsActive; }
    public void setPhysicsActive(boolean v)  { physicsActive = v; }

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
