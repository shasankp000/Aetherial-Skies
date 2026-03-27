# Ship Architecture — Part 4: Boarding, Interaction Proxy & Player Movement

> **For GitHub Copilot:** This is Part 4 of 4. Complete Parts 1–3 first. This part covers: player boarding/dismounting, the inverse-transform interaction raycast so right-clicking blocks on a moving ship works, and the WASD input rotation mixin so the player walks relative to the ship's heading.

---

## 1. Boarding System

Boarding is no longer tied to `BoatEntity.interact()`. Instead, the `ShipCrateItem` (or a new `ShipHelmBlock`) triggers boarding directly. For now we implement boarding via right-clicking the helm block while standing near the ship.

### 1.1 `ShipBoardingHandler.java`

Create: `src/main/java/net/shasankp000/Ship/ShipBoardingHandler.java`

```java
package net.shasankp000.Ship;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.Packet.ShipBoardS2CPacket;
import net.shasankp000.Ship.Structure.ShipStructure;
import net.shasankp000.Ship.Structure.ShipStructureManager;
import net.shasankp000.Ship.Transform.ShipTransform;

import java.util.UUID;

public final class ShipBoardingHandler {

    private ShipBoardingHandler() {}

    /**
     * Boards a player onto a ship.
     * Teleports them to the helm world position and registers them as boarded.
     */
    public static void board(ServerPlayerEntity player, ShipStructure ship) {
        Vec3d helmPos = ship.getHelmWorldPos();
        player.teleport(
            (ServerWorld) player.getWorld(),
            helmPos.x, helmPos.y, helmPos.z,
            player.getYaw(), player.getPitch()
        );
        ship.addBoardedPlayer(player.getUuid());
        new ShipBoardS2CPacket(ship.getShipId(), true).send(player);
    }

    /**
     * Dismounts a player from a ship.
     */
    public static void dismount(ServerPlayerEntity player, UUID shipId) {
        ShipStructure ship = ShipStructureManager.getInstance().getShip(shipId);
        if (ship != null) {
            ship.removeBoardedPlayer(player.getUuid());
        }
        new ShipBoardS2CPacket(null, false).send(player);
    }

    /**
     * Finds the nearest deployed ship to the player within range blocks.
     * Used by the helm block interaction to find which ship to board.
     */
    public static ShipStructure findNearestShip(ServerPlayerEntity player, double range) {
        Vec3d pPos = player.getPos();
        ShipStructure nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (ShipStructure ship : ShipStructureManager.getInstance().getAllShips()) {
            double dist = ship.getTransform().worldOffset().distanceTo(pPos);
            if (dist < range && dist < nearestDist) {
                nearestDist = dist;
                nearest = ship;
            }
        }
        return nearest;
    }
}
```

### 1.2 Helm Block Interaction

The helm block's `onUse` method in `ShipHelmBlock.java` (existing file) should call `ShipBoardingHandler.board()` when right-clicked:

```java
@Override
public ActionResult onUse(BlockState state, World world, BlockPos pos,
                          PlayerEntity player, Hand hand, BlockHitResult hit) {
    if (world.isClient()) return ActionResult.SUCCESS;

    ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;

    // Check if player is already on a ship — if so, dismount
    // (Client sends dismount intent via a separate packet or sneak+right-click)
    // For now: right-click boards, sneak+right-click dismounts
    if (player.isSneaking()) {
        // Find which ship this helm belongs to by scanning ShipStructureManager
        UUID ridingShipId = getRidingShipId(serverPlayer);
        if (ridingShipId != null) {
            ShipBoardingHandler.dismount(serverPlayer, ridingShipId);
        }
        return ActionResult.SUCCESS;
    }

    ShipStructure ship = ShipBoardingHandler.findNearestShip(serverPlayer, 8.0);
    if (ship != null) {
        ShipBoardingHandler.board(serverPlayer, ship);
    }
    return ActionResult.SUCCESS;
}

private static UUID getRidingShipId(ServerPlayerEntity player) {
    for (ShipStructure ship : ShipStructureManager.getInstance().getAllShips()) {
        if (ship.getBoardedPlayers().contains(player.getUuid())) {
            return ship.getShipId();
        }
    }
    return null;
}
```

---

## 2. Interaction Proxy — Inverse Transform Raycast

When the player right-clicks while aboard a moving ship, the block they aim at is at its *rendered* position (transform-offset world space), but the actual block exists at its *storage* position. We intercept the server-side interaction and remap the hit block position.

### 2.1 `MixinServerPlayerInteraction.java`

Create: `src/main/java/net/shasankp000/mixin/MixinServerPlayerInteraction.java`

```java
package net.shasankp000.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.shasankp000.Ship.Dimension.ShipStorageDimension;
import net.shasankp000.Ship.Structure.ShipStructure;
import net.shasankp000.Ship.Structure.ShipStructureManager;
import net.shasankp000.Ship.Transform.ShipTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerInteractionManager.class)
public class MixinServerPlayerInteraction {

    @Shadow private ServerPlayerEntity player;

    /**
     * When a player interacts with a block while aboard a ship, the hit position
     * is in rendered world space. We inverse-transform it back to storage-dim
     * block coords and dispatch the interaction there instead.
     */
    @Inject(
        method = "interactBlock",
        at = @At("HEAD"),
        cancellable = true
    )
    private void aetherial$proxyShipBlockInteraction(
        ServerPlayerEntity player,
        World world,
        net.minecraft.item.ItemStack stack,
        Hand hand,
        BlockHitResult hitResult,
        CallbackInfoReturnable<ActionResult> cir
    ) {
        // Only intercept if this player is aboard a ship
        ShipStructure ship = getShipForPlayer(player);
        if (ship == null) return;

        ShipTransform t = ship.getTransform();
        Vec3d hitWorld = Vec3d.ofCenter(hitResult.getBlockPos());

        // Inverse-transform hit position from world space → ship-local space
        Vec3d localPos = ShipTransform.worldToLocal(t, hitWorld);

        // Map local pos → storage dimension block pos
        BlockPos storagePos = ShipSlotAllocator.localToStorage(
            ship.getShipId(), localPos
        );

        // Get the storage world and dispatch the interaction there
        net.minecraft.server.world.ServerWorld storageWorld =
            player.getServer().getWorld(ShipStorageDimension.DIMENSION_KEY);
        if (storageWorld == null) return;

        BlockState stateAtStorage = storageWorld.getBlockState(storagePos);
        if (stateAtStorage.isAir()) return; // miss — let vanilla handle it

        // Dispatch block interaction in storage world at storage pos
        BlockHitResult remappedHit = new BlockHitResult(
            Vec3d.ofCenter(storagePos),
            hitResult.getSide(),
            storagePos,
            false
        );
        ActionResult result = stateAtStorage.onUse(
            storageWorld, player, hand, remappedHit
        );
        cir.setReturnValue(result);
    }

    private static ShipStructure getShipForPlayer(ServerPlayerEntity player) {
        for (ShipStructure ship : ShipStructureManager.getInstance().getAllShips()) {
            if (ship.getBoardedPlayers().contains(player.getUuid())) {
                return ship;
            }
        }
        return null;
    }
}
```

> **Note for Copilot:** `ShipSlotAllocator` is imported from Part 1. Ensure the import is `net.shasankp000.Ship.Structure.ShipSlotAllocator`.

---

## 3. Player Movement — Rotate Input to Ship-Local Frame

When aboard a ship, pressing W should move the player toward the ship's bow regardless of the ship's yaw. This uses the same mixin concept from the previous architecture.

### 3.1 `MixinClientPlayerMovement.java`

Create: `src/main/java/net/shasankp000/mixin/MixinClientPlayerMovement.java`

```java
package net.shasankp000.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Client.ShipTransformClientCache;
import net.shasankp000.Ship.Transform.ShipTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.UUID;

@Mixin(net.minecraft.entity.LivingEntity.class)
public class MixinClientPlayerMovement {

    @ModifyVariable(
        method = "travel",
        at = @At("HEAD"),
        argsOnly = true,
        index = 1
    )
    private Vec3d aetherial$rotateInputToShipLocal(Vec3d movementInput) {
        if (!((Object) this instanceof ClientPlayerEntity player)) {
            return movementInput;
        }
        ShipTransformClientCache cache = ShipTransformClientCache.getInstance();
        if (!cache.isAboard()) return movementInput;

        UUID shipId = cache.getBoardedShipId();
        ShipTransform transform = cache.get(shipId);
        if (transform == null) return movementInput;

        float shipYaw   = transform.yaw();
        float playerYaw = player.getYaw();
        double rad = Math.toRadians(playerYaw - shipYaw);
        double cos = Math.cos(rad), sin = Math.sin(rad);

        return new Vec3d(
            movementInput.x * cos - movementInput.z * sin,
            movementInput.y,
            movementInput.x * sin + movementInput.z * cos
        );
    }
}
```

---

## 4. Update `aetherial-skies.mixins.json`

The `"mixins"` array should now be:

```json
"mixins": [
  "MixinServerPlayerInteraction",
  "MixinClientPlayerMovement"
]
```

All old mixins (`BoatEntityMixin`, `EntityTickInvoker`) are deleted per Part 1.

---

## 5. Wire Deploy Into `ShipCrateItem`

The `ShipCrateItem.use()` method (or its event handler) currently calls `ShipDeployService.deployFromCrate()`. Replace that call with:

```java
// In ShipCrateItem.use() or its UseBlockCallback handler:
ShipHullData hullData = ShipHullData.fromCrateTag(crateTag);
Vec3d waterPos = findWaterSurfacePos(world, clickedPos); // same logic as old ShipDeployService.findSpawnPosition
float yaw = player.getYaw() - hullData.helmYawDegrees();

ShipStructure ship = ShipStructureManager.getInstance().deploy(hullData, waterPos, yaw);
// ShipStructureManager.deploy() broadcasts ShipDeployS2CPacket automatically (see Part 3)
```

---

## 6. Part 4 Checklist

- [ ] `ShipBoardingHandler.java` created
- [ ] `ShipHelmBlock.onUse()` calls `ShipBoardingHandler.board()` / `dismount()`
- [ ] `MixinServerPlayerInteraction.java` created
- [ ] `MixinClientPlayerMovement.java` created
- [ ] Both new mixins registered in `aetherial-skies.mixins.json`
- [ ] `ShipCrateItem` deploy wired to `ShipStructureManager.deploy()`
- [ ] Old `ShipDeployService.java` deleted
- [ ] Project compiles with no errors
- [ ] **In-game test:** deploy ship → visible at waterline ✓
- [ ] **In-game test:** right-click helm block → player teleports to helm offset ✓
- [ ] **In-game test:** WASD while aboard → player moves relative to ship heading ✓
- [ ] **In-game test:** right-click chest on ship → chest opens (interaction proxy works) ✓
- [ ] **In-game test:** sneak + right-click helm → player dismounts ✓
- [ ] **In-game test:** world reload → ship re-appears at correct position ✓

---

## 7. Persistence (World Reload)

For ships to survive world reload, `ShipStructureManager` must save/load its state. Hook into `ServerWorldEvents.SAVE` and write each ship's `hullData.toEntityTag()` + transform to a `PersistentState`:

```java
// ShipPersistentState extends PersistentState
// Key: "aetherial_skies_ships"
// Saves: for each ship — shipId, hullData NBT, worldOffset, yaw
// On load: calls ShipStructureManager.deploy() for each saved entry (skips re-placing blocks if slot already populated)
```

Implement `ShipPersistentState.java` following the standard Fabric `PersistentState` pattern. This is straightforward NBT serialization and is left as a follow-up task after the core rendering is confirmed working.
