# Ship Architecture — Part 1: Structure Storage & Ship Dimension

> **For GitHub Copilot:** This is Part 1 of a 4-part implementation series. Read all four parts before writing any code. This part covers the `ShipStructure` data model, the dedicated ship-storage dimension, and the block claiming/releasing lifecycle.
>
> **This is a complete architectural replacement** of the previous `ShipBoatEntity`-based system. The `ShipBoatEntity`, `ShipPhysicsEngine`, `ShipPhysicsState`, `ShipDeployService`, and `BoatEntityMixin` from the old system are **all deleted** as part of this implementation. Keep `ShipHullData`, `ShipCrateService`, `ShipCompileService`, and `ShipSelectionManager` — they feed data into the new system.
>
> Target branch: `1.20.1`. All paths relative to project root.

---

## 0. Why This Architecture

Minecraft blocks have native support for right-click interactions (`BlockState.onUse`), block entities (chests, signs), comparator outputs, and redstone. None of these work when blocks are merely *visual* geometry attached to a moving entity. The previous `ShipBoatEntity` approach faked block rendering on an entity, which made interactable blocks permanently impossible.

This new architecture keeps blocks as **real blocks in the world at all times**. Movement is achieved by maintaining a `ShipTransform` (position offset + yaw) and applying it purely at render time on the client. The blocks never physically move during sailing — only the transform changes. When the ship docks, blocks are physically relocated once.

```
Anchor dimension (ship_storage):     Rendered position in overworld:
  Block at (slotX,  64, slotZ)   →   slotPos + worldOffset, rotated by yaw
  Block at (slotX+1, 64, slotZ)  →   (slotPos+1) + worldOffset, rotated by yaw
  (blocks never move)                (transform changes every tick)
```

---

## 1. Package Layout (New)

```
net.shasankp000.Ship/
  Structure/
    ShipStructure.java          ← data model for one ship's block set
    ShipStructureManager.java   ← server singleton: all active ships
    ShipSlotAllocator.java      ← assigns non-overlapping XZ regions in ship_storage dim
  Transform/
    ShipTransform.java          ← position offset + yaw + velocity (server + client)
    ShipTransformManager.java   ← server: owns physics tick loop for all ships
  Physics/
    ShipPhysicsEngine.java      ← REWRITTEN: operates on ShipTransform, not an entity
    ShipPhysicsConfig.java      ← renamed from PhysicsConfig (keep same constants)
  Packet/
    ShipTransformSyncS2CPacket.java   ← syncs transform to all tracking clients each tick
    ShipBoardS2CPacket.java           ← tells client they are aboard a specific ship
  Dimension/
    ShipStorageDimension.java   ← registers the ship_storage dimension
  Item/
    (keep existing ShipCrateItem structure)
net.shasankp000.Client/
  Render/
    ShipStructureRenderer.java  ← client: renders each ship's blocks using its transform
  ShipTransformClientCache.java ← client: stores last-known transform per ship UUID
net.shasankp000.mixin/
  MixinServerPlayerInteraction.java  ← inverse-transform block interaction raycast
  MixinClientPlayerMovement.java     ← offsets player movement input by ship yaw
```

---

## 2. The Ship Storage Dimension

### 2.1 Register `ship_storage`

Create: `src/main/java/net/shasankp000/Ship/Dimension/ShipStorageDimension.java`

This dimension is a flat, void world where ship block structures are stored at fixed "slots". Players cannot enter it — it has no portal or travel mechanism. It is purely a server-side block storage backend.

```java
package net.shasankp000.Ship.Dimension;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

public final class ShipStorageDimension {

    private ShipStorageDimension() {}

    public static final RegistryKey<World> DIMENSION_KEY = RegistryKey.of(
        RegistryKeys.WORLD,
        new Identifier("aetherial-skies", "ship_storage")
    );

    /**
     * Y level at which all ship structures are stored in the storage dimension.
     * All blocks sit on this Y layer (Y=64 is conventional sea level).
     */
    public static final int STORAGE_Y = 64;

    /**
     * Returns true if the given world key is the ship storage dimension.
     * Use this to guard against players or mobs interacting with storage blocks directly.
     */
    public static boolean isShipStorageWorld(RegistryKey<World> key) {
        return DIMENSION_KEY.equals(key);
    }
}
```

Create the dimension JSON files:

**`src/main/resources/data/aetherial-skies/dimension/ship_storage.json`**
```json
{
  "type": "aetherial-skies:ship_storage",
  "generator": {
    "type": "minecraft:flat",
    "settings": {
      "layers": [
        { "block": "minecraft:bedrock", "height": 1 },
        { "block": "minecraft:air",    "height": 127 }
      ],
      "biome": "minecraft:the_void",
      "features": false,
      "lakes": false
    }
  }
}
```

**`src/main/resources/data/aetherial-skies/dimension_type/ship_storage.json`**
```json
{
  "ultrawarm": false,
  "natural": false,
  "coordinate_scale": 1.0,
  "has_skylight": true,
  "has_ceiling": false,
  "ambient_light": 1.0,
  "fixed_time": 6000,
  "monster_spawn_block_light_limit": 0,
  "monster_spawn_light_level": { "type": "minecraft:constant", "value": 0 },
  "piglin_safe": true,
  "bed_works": false,
  "respawn_anchor_works": false,
  "has_raids": false,
  "logical_height": 256,
  "min_y": 0,
  "height": 256,
  "infiniburn": "#minecraft:infiniburn_overworld",
  "effects": "minecraft:overworld"
}
```

> `ambient_light: 1.0` ensures blocks in the storage dimension are always fully lit, so their texture capture for rendering uses correct brightness. `fixed_time: 6000` keeps it permanently at noon.

---

## 3. `ShipSlotAllocator.java`

Create: `src/main/java/net/shasankp000/Ship/Structure/ShipSlotAllocator.java`

Assigns non-overlapping rectangular regions in the storage dimension, one per ship. Each slot is `SLOT_SIZE × SLOT_SIZE` blocks in XZ. Ships are packed in a grid.

```java
package net.shasankp000.Ship.Structure;

import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ShipSlotAllocator {

    // Each slot is 64x64 blocks. Max ship size is 5x3 currently;
    // 64x64 gives plenty of margin for larger ships in future.
    public static final int SLOT_SIZE = 64;

    private static final ConcurrentHashMap<UUID, BlockPos> shipToSlotOrigin = new ConcurrentHashMap<>();
    private static final Set<Integer> usedSlots = ConcurrentHashMap.newKeySet();
    private static int nextSlotIndex = 0;

    private ShipSlotAllocator() {}

    /**
     * Allocates a storage slot for a ship and returns the slot's origin BlockPos
     * in the ship_storage dimension (Y = ShipStorageDimension.STORAGE_Y).
     */
    public static synchronized BlockPos allocate(UUID shipId) {
        if (shipToSlotOrigin.containsKey(shipId)) {
            return shipToSlotOrigin.get(shipId);
        }
        int slotIndex = nextSlotIndex++;
        // Pack slots in a row along the X axis
        int slotX = slotIndex * SLOT_SIZE;
        BlockPos origin = new BlockPos(slotX, ShipStorageDimension.STORAGE_Y, 0);
        shipToSlotOrigin.put(shipId, origin);
        usedSlots.add(slotIndex);
        return origin;
    }

    /**
     * Releases the slot held by this ship (called on ship destruction).
     */
    public static synchronized void release(UUID shipId) {
        shipToSlotOrigin.remove(shipId);
    }

    /**
     * Returns the previously allocated slot origin for a ship, or null if none.
     */
    public static BlockPos getSlotOrigin(UUID shipId) {
        return shipToSlotOrigin.get(shipId);
    }

    /**
     * Converts a block's local offset (from ShipHullData.PackedBlock) to
     * its absolute position in the ship_storage dimension.
     */
    public static BlockPos localToStorage(UUID shipId, net.minecraft.util.math.Vec3d localOffset) {
        BlockPos slotOrigin = shipToSlotOrigin.get(shipId);
        if (slotOrigin == null) return BlockPos.ORIGIN;
        return slotOrigin.add(
            (int) Math.round(localOffset.x),
            (int) Math.round(localOffset.y),
            (int) Math.round(localOffset.z)
        );
    }
}
```

---

## 4. `ShipStructure.java`

Create: `src/main/java/net/shasankp000/Ship/Structure/ShipStructure.java`

Represents one ship's complete state: its block positions in the storage dimension, its current transform, its helm offset, and its physics parameters.

```java
package net.shasankp000.Ship.Structure;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.ShipHullData;
import net.shasankp000.Ship.Transform.ShipTransform;

import java.util.List;
import java.util.UUID;

/**
 * Server-side representation of a deployed ship.
 * Blocks live at fixed positions in the ship_storage dimension.
 * The ShipTransform controls where they appear to be in the overworld.
 */
public class ShipStructure {

    private final UUID shipId;
    private final ShipHullData hullData;
    private final BlockPos slotOrigin;       // origin in ship_storage dimension
    private ShipTransform transform;          // current world-space transform
    private boolean physicsActive = true;

    // Players currently aboard this ship (server-side UUIDs)
    private final java.util.Set<UUID> boardedPlayers = new java.util.HashSet<>();

    public ShipStructure(UUID shipId, ShipHullData hullData, BlockPos slotOrigin, ShipTransform initialTransform) {
        this.shipId = shipId;
        this.hullData = hullData;
        this.slotOrigin = slotOrigin;
        this.transform = initialTransform;
    }

    public UUID getShipId()             { return shipId; }
    public ShipHullData getHullData()   { return hullData; }
    public BlockPos getSlotOrigin()     { return slotOrigin; }
    public ShipTransform getTransform() { return transform; }
    public void setTransform(ShipTransform t) { this.transform = t; }
    public boolean isPhysicsActive()    { return physicsActive; }
    public void setPhysicsActive(boolean v) { physicsActive = v; }

    public void addBoardedPlayer(UUID playerId)    { boardedPlayers.add(playerId); }
    public void removeBoardedPlayer(UUID playerId) { boardedPlayers.remove(playerId); }
    public java.util.Set<UUID> getBoardedPlayers() { return java.util.Collections.unmodifiableSet(boardedPlayers); }

    /**
     * Returns the world-space position a player should be placed at when
     * boarding this ship at the helm, accounting for current transform.
     */
    public Vec3d getHelmWorldPos() {
        Vec3d helm = hullData.helmOffset();
        return ShipTransform.localToWorld(transform, helm).add(0, 0.7, 0);
    }

    /**
     * All block positions in the ship_storage dimension for this structure.
     */
    public List<BlockPos> getStoragePositions() {
        return hullData.blocks().stream()
            .map(b -> ShipSlotAllocator.localToStorage(shipId, b.localOffset()))
            .toList();
    }
}
```

---

## 5. `ShipStructureManager.java`

Create: `src/main/java/net/shasankp000/Ship/Structure/ShipStructureManager.java`

Server singleton that owns all active `ShipStructure` instances. Handles deploy (place blocks in storage dim), destroy (remove blocks), and save/load via world NBT.

```java
package net.shasankp000.Ship.Structure;

import net.minecraft.block.BlockState;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.shasankp000.Ship.Dimension.ShipStorageDimension;
import net.shasankp000.Ship.ShipHullData;
import net.shasankp000.Ship.Transform.ShipTransform;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ShipStructureManager {

    private static ShipStructureManager INSTANCE = null;
    private final Map<UUID, ShipStructure> ships = new ConcurrentHashMap<>();
    private MinecraftServer server;

    private ShipStructureManager() {}

    public static ShipStructureManager getInstance() {
        if (INSTANCE == null) INSTANCE = new ShipStructureManager();
        return INSTANCE;
    }

    public void init(MinecraftServer server) {
        this.server = server;
    }

    /**
     * Deploys a ship from a crate into the storage dimension and registers it.
     *
     * @param hullData     the hull data from the ship crate item
     * @param worldPos     where the ship should appear in the overworld (waterline position)
     * @param initialYaw   initial facing yaw in degrees
     * @return the newly created ShipStructure
     */
    public ShipStructure deploy(ShipHullData hullData, Vec3d worldPos, float initialYaw) {
        UUID shipId = hullData.shipId();
        BlockPos slotOrigin = ShipSlotAllocator.allocate(shipId);

        ServerWorld storageWorld = getStorageWorld();
        if (storageWorld == null) {
            throw new IllegalStateException("ship_storage dimension not loaded");
        }

        // Place real blocks in storage dimension
        for (net.shasankp000.Ship.ShipCrateService.PackedBlock pb : hullData.blocks()) {
            BlockPos storagePos = ShipSlotAllocator.localToStorage(shipId, pb.localOffset());
            BlockState state = net.minecraft.registry.Registries.BLOCK
                .get(new net.minecraft.util.Identifier(pb.blockId()))
                .getDefaultState();
            storageWorld.setBlockState(storagePos, state);
        }

        // Compute initial transform: worldOffset is worldPos relative to structureOrigin
        // structureOrigin = centre of the hull bounds in world space
        ShipHullData.HullBounds bounds = hullData.computeBounds();
        Vec3d structureOrigin = new Vec3d(
            slotOrigin.getX() + (bounds.minX() + bounds.maxX()) * 0.5,
            slotOrigin.getY() + (bounds.minY() + bounds.maxY()) * 0.5,
            slotOrigin.getZ() + (bounds.minZ() + bounds.maxZ()) * 0.5
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

    /**
     * Destroys a ship: removes blocks from storage, releases slot, deregisters.
     */
    public void destroy(UUID shipId) {
        ShipStructure structure = ships.remove(shipId);
        if (structure == null) return;

        ServerWorld storageWorld = getStorageWorld();
        if (storageWorld != null) {
            for (BlockPos pos : structure.getStoragePositions()) {
                storageWorld.setBlockState(pos, net.minecraft.block.Blocks.AIR.getDefaultState());
            }
        }
        ShipSlotAllocator.release(shipId);
    }

    public ShipStructure getShip(UUID shipId) {
        return ships.get(shipId);
    }

    public Collection<ShipStructure> getAllShips() {
        return Collections.unmodifiableCollection(ships.values());
    }

    private ServerWorld getStorageWorld() {
        if (server == null) return null;
        return server.getWorld(ShipStorageDimension.DIMENSION_KEY);
    }
}
```

---

## 6. Delete Old Files

The following files from the previous architecture must be deleted:

- `src/main/java/net/shasankp000/Entity/ShipBoatEntity.java`
- `src/main/java/net/shasankp000/Ship/Physics/ShipPhysicsEngine.java`
- `src/main/java/net/shasankp000/Ship/Physics/ShipPhysicsState.java`
- `src/main/java/net/shasankp000/Ship/ShipDeployService.java`
- `src/main/java/net/shasankp000/mixin/BoatEntityMixin.java`
- `src/main/java/net/shasankp000/mixin/EntityTickInvoker.java`
- Remove `BoatEntityMixin` and `EntityTickInvoker` from `aetherial-skies.mixins.json`
- Remove `ShipBoatEntity` registration from `ModEntityTypes.java`

Keep:
- `ShipHullData.java` (used by new system)
- `ShipCrateService.java` (used by new system)
- `ShipCompileService.java` (used by new system)
- `ShipSelectionManager.java` (used by new system)
- `PhysicsConfig.java` → rename to `ShipPhysicsConfig.java`, same constants

---

## 7. Part 1 Checklist

- [ ] `ShipStorageDimension.java` created
- [ ] `data/aetherial-skies/dimension/ship_storage.json` created
- [ ] `data/aetherial-skies/dimension_type/ship_storage.json` created
- [ ] `ShipSlotAllocator.java` created
- [ ] `ShipStructure.java` created
- [ ] `ShipStructureManager.java` created
- [ ] Old files listed in Section 6 deleted
- [ ] Project compiles (some classes will have unresolved refs to Part 2's `ShipTransform` — that is expected and will be resolved in Part 2)
- [ ] **Do not implement Part 2 until Part 1 compiles**
