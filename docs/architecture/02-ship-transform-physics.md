# Ship Architecture — Part 2: Transform, Physics & Network Sync

> **For GitHub Copilot:** This is Part 2 of 4. Complete Part 1 first. This part covers `ShipTransform`, the rewritten `ShipPhysicsEngine`, the S2C sync packets, and the server tick loop. After this part, the server should be computing ship physics each tick and broadcasting transforms to clients.

---

## 1. `ShipTransform.java`

Create: `src/main/java/net/shasankp000/Ship/Transform/ShipTransform.java`

Immutable value type. The server updates this every tick; the client stores the latest received copy per ship.

```java
package net.shasankp000.Ship.Transform;

import net.minecraft.util.math.Vec3d;

/**
 * Represents the full spatial state of a ship at one point in time.
 *
 * structureOrigin: the fixed centre of the block structure in ship_storage coords.
 *                  This never changes after deployment.
 * worldOffset:     where structureOrigin appears in the overworld. Updated by physics.
 * yaw:             rotation around the Y axis in degrees. Updated by physics.
 * velocity:        current velocity vector (blocks/tick). Used by physics and client interpolation.
 */
public record ShipTransform(
    Vec3d structureOrigin,
    Vec3d worldOffset,
    float yaw,
    Vec3d velocity
) {
    /**
     * Converts a position in ship-local space (relative to structureOrigin)
     * to world space by applying this transform.
     */
    public static Vec3d localToWorld(ShipTransform t, Vec3d localPos) {
        double rad = Math.toRadians(t.yaw());
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double rotX = localPos.x * cos - localPos.z * sin;
        double rotZ = localPos.x * sin + localPos.z * cos;
        return t.worldOffset().add(rotX, localPos.y, rotZ);
    }

    /**
     * Converts a world-space position back to ship-local space.
     * Used by the interaction proxy to find which block was clicked.
     */
    public static Vec3d worldToLocal(ShipTransform t, Vec3d worldPos) {
        Vec3d relative = worldPos.subtract(t.worldOffset());
        double rad = Math.toRadians(-t.yaw());
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double localX = relative.x * cos - relative.z * sin;
        double localZ = relative.x * sin + relative.z * cos;
        return new Vec3d(localX, relative.y, localZ);
    }

    /** Returns a new transform with updated worldOffset and velocity. */
    public ShipTransform withMotion(Vec3d newOffset, float newYaw, Vec3d newVelocity) {
        return new ShipTransform(this.structureOrigin, newOffset, newYaw, newVelocity);
    }
}
```

---

## 2. `ShipPhysicsEngine.java` (Rewritten)

Create: `src/main/java/net/shasankp000/Ship/Physics/ShipPhysicsEngine.java`

This replaces the old version entirely. It now operates on `ShipTransform` instead of a `BoatEntity`. No entity references anywhere in this class.

```java
package net.shasankp000.Ship.Physics;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.shasankp000.Ship.ShipHullData;
import net.shasankp000.Ship.Transform.ShipTransform;

public class ShipPhysicsEngine {

    private final World world;          // overworld reference for water scanning
    private final ShipHullData hullData;
    private final ShipHullData.HullBounds bounds;

    private double cachedWaterSurfaceY = Double.NaN;
    private int waterCacheTicks = 0;
    private static final int WATER_CACHE_TTL = 5;

    public ShipPhysicsEngine(World world, ShipHullData hullData) {
        this.world = world;
        this.hullData = hullData;
        this.bounds = hullData.computeBounds();
    }

    /**
     * Advances physics by one tick.
     * @param current  the transform at the start of this tick
     * @return         the new transform after physics
     */
    public ShipTransform tick(ShipTransform current) {
        float s = calculateSubmersion(current);

        float targetSubmersion = Math.max(0.01f, hullData.effectiveRelativeDensity());
        double displacementError = s - targetSubmersion;
        double buoyancyForce = (s > 0f)
            ? -displacementError * ShipPhysicsConfig.BUOYANCY_STIFFNESS * hullData.mass()
            : 0.0;

        double gravityForce   = -ShipPhysicsConfig.GRAVITY * hullData.mass();
        double dragCoeff      = ShipPhysicsConfig.AIR_DRAG + (s * ShipPhysicsConfig.WATER_DRAG);
        Vec3d  drag           = current.velocity().multiply(-dragCoeff);

        double accelY = (gravityForce + buoyancyForce + drag.y) / hullData.mass();
        double accelX = drag.x / hullData.mass();
        double accelZ = drag.z / hullData.mass();

        Vec3d newVel = new Vec3d(
            current.velocity().x + accelX,
            current.velocity().y + accelY,
            current.velocity().z + accelZ
        );
        newVel = clipVelocity(newVel);

        Vec3d newOffset = current.worldOffset().add(newVel);

        // Settle damping
        if (newVel.lengthSquared() < ShipPhysicsConfig.SETTLE_THRESHOLD * ShipPhysicsConfig.SETTLE_THRESHOLD) {
            newVel = newVel.multiply(ShipPhysicsConfig.SETTLE_DAMPING);
        }

        return current.withMotion(newOffset, current.yaw(), newVel);
    }

    private float calculateSubmersion(ShipTransform t) {
        if (waterCacheTicks <= 0 || Double.isNaN(cachedWaterSurfaceY)) {
            cachedWaterSurfaceY = findWaterSurfaceY(t.worldOffset());
            waterCacheTicks = WATER_CACHE_TTL;
        } else {
            waterCacheTicks--;
        }

        // worldOffset is the ship's centre in the overworld
        // hull bottom world Y = worldOffset.y + bounds.minY()
        double hullBottomY = t.worldOffset().y + bounds.minY();
        double hullHeight  = bounds.height();
        if (hullHeight <= 0) return 0f;

        double submergedDepth = MathHelper.clamp(cachedWaterSurfaceY - hullBottomY, 0.0, hullHeight);
        return (float)(submergedDepth / hullHeight);
    }

    private double findWaterSurfaceY(Vec3d worldOffset) {
        int scanX = MathHelper.floor(worldOffset.x);
        int scanZ = MathHelper.floor(worldOffset.z);
        double hullBottomY = worldOffset.y + bounds.minY();
        int startY = MathHelper.floor(hullBottomY) - 1;
        int maxY   = startY + MathHelper.ceil((float)bounds.height()) + 6;

        double lastWaterY = hullBottomY;
        boolean sawWater  = false;
        for (int y = startY; y <= maxY; y++) {
            BlockPos pos = new BlockPos(scanX, y, scanZ);
            if (!world.getFluidState(pos).isEmpty()) {
                sawWater  = true;
                lastWaterY = y + 1.0;
            } else if (sawWater) {
                break;
            }
        }
        return lastWaterY;
    }

    private Vec3d clipVelocity(Vec3d v) {
        double speed = v.length();
        return speed > ShipPhysicsConfig.MAX_VELOCITY ? v.normalize().multiply(ShipPhysicsConfig.MAX_VELOCITY) : v;
    }
}
```

---

## 3. `ShipTransformManager.java` — Server Tick Loop

Create: `src/main/java/net/shasankp000/Ship/Transform/ShipTransformManager.java`

Called every server tick from `ServerTickEvents.END_SERVER_TICK`. Advances physics for every ship and queues transform sync packets.

```java
package net.shasankp000.Ship.Transform;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.shasankp000.Ship.Physics.ShipPhysicsEngine;
import net.shasankp000.Ship.Structure.ShipStructure;
import net.shasankp000.Ship.Structure.ShipStructureManager;
import net.shasankp000.Ship.Packet.ShipTransformSyncS2CPacket;

public final class ShipTransformManager {

    private ShipTransformManager() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(ShipTransformManager::tick);
    }

    private static void tick(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        ShipStructureManager mgr = ShipStructureManager.getInstance();

        for (ShipStructure ship : mgr.getAllShips()) {
            if (!ship.isPhysicsActive()) continue;

            ShipPhysicsEngine engine = new ShipPhysicsEngine(overworld, ship.getHullData());
            ShipTransform newTransform = engine.tick(ship.getTransform());
            ship.setTransform(newTransform);

            // Broadcast to all players tracking this ship
            ShipTransformSyncS2CPacket packet = new ShipTransformSyncS2CPacket(
                ship.getShipId(), newTransform
            );
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                packet.send(player);
            }

            // Move boarded players with the ship
            Vec3d delta = newTransform.worldOffset().subtract(ship.getTransform().worldOffset());
            float yawDelta = net.minecraft.util.math.MathHelper.wrapDegrees(
                newTransform.yaw() - ship.getTransform().yaw()
            );
            for (java.util.UUID playerId : ship.getBoardedPlayers()) {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
                if (player == null) continue;
                // Rotate player around ship centre
                net.minecraft.util.math.Vec3d pPos = player.getPos().add(delta);
                if (Math.abs(yawDelta) > 0.001f) {
                    double dx  = pPos.x - newTransform.worldOffset().x;
                    double dz  = pPos.z - newTransform.worldOffset().z;
                    double rad = Math.toRadians(yawDelta);
                    double cos = Math.cos(rad), sin = Math.sin(rad);
                    pPos = new net.minecraft.util.math.Vec3d(
                        newTransform.worldOffset().x + dx * cos - dz * sin,
                        pPos.y,
                        newTransform.worldOffset().z + dx * sin + dz * cos
                    );
                }
                player.teleport(
                    (ServerWorld) player.getWorld(),
                    pPos.x, pPos.y, pPos.z,
                    player.getYaw(), player.getPitch()
                );
            }
        }
    }
}
```

> **Note for Copilot:** `ShipPhysicsEngine` is instantiated inside the loop here for clarity. If performance profiling later shows allocation pressure, cache one engine per ship inside `ShipStructure`.

---

## 4. Network Packets

### 4.1 `ShipTransformSyncS2CPacket.java`

Create: `src/main/java/net/shasankp000/Ship/Packet/ShipTransformSyncS2CPacket.java`

Sent server → client every tick for each active ship. Carries the full transform.

```java
package net.shasankp000.Ship.Packet;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.Transform.ShipTransform;

import java.util.UUID;

public record ShipTransformSyncS2CPacket(UUID shipId, ShipTransform transform) {

    public static final Identifier ID =
        new Identifier("aetherial-skies", "ship_transform_sync");

    public void send(ServerPlayerEntity player) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(shipId);
        writeVec3d(buf, transform.structureOrigin());
        writeVec3d(buf, transform.worldOffset());
        buf.writeFloat(transform.yaw());
        writeVec3d(buf, transform.velocity());
        ServerPlayNetworking.send(player, ID, buf);
    }

    public static ShipTransformSyncS2CPacket read(PacketByteBuf buf) {
        UUID shipId          = buf.readUuid();
        Vec3d structureOrigin = readVec3d(buf);
        Vec3d worldOffset     = readVec3d(buf);
        float yaw             = buf.readFloat();
        Vec3d velocity        = readVec3d(buf);
        return new ShipTransformSyncS2CPacket(
            shipId,
            new ShipTransform(structureOrigin, worldOffset, yaw, velocity)
        );
    }

    private static void writeVec3d(PacketByteBuf buf, Vec3d v) {
        buf.writeDouble(v.x); buf.writeDouble(v.y); buf.writeDouble(v.z);
    }
    private static Vec3d readVec3d(PacketByteBuf buf) {
        return new Vec3d(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }
}
```

### 4.2 `ShipBoardS2CPacket.java`

Create: `src/main/java/net/shasankp000/Ship/Packet/ShipBoardS2CPacket.java`

Tells the client they have boarded (or left) a specific ship, so the renderer knows to apply the local-frame movement mixin.

```java
package net.shasankp000.Ship.Packet;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.UUID;

public record ShipBoardS2CPacket(UUID shipId, boolean boarding) {

    public static final Identifier ID =
        new Identifier("aetherial-skies", "ship_board");

    /** boarding=true when player gets on, boarding=false when they leave */
    public void send(ServerPlayerEntity player) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBoolean(boarding);
        if (boarding) buf.writeUuid(shipId);
        ServerPlayNetworking.send(player, ID, buf);
    }

    public static ShipBoardS2CPacket read(PacketByteBuf buf) {
        boolean boarding = buf.readBoolean();
        UUID shipId = boarding ? buf.readUuid() : null;
        return new ShipBoardS2CPacket(shipId, boarding);
    }
}
```

### 4.3 Register Packets in `AetherialSkies.java` and `AetherialSkiesClient.java`

In `AetherialSkies.java` `onInitialize()`:
```java
// Register server tick loop
ShipTransformManager.register();

// Init structure manager
net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
    ShipStructureManager.getInstance().init(server);
});
```

In `AetherialSkiesClient.java` `onInitializeClient()`:
```java
// Register incoming packet handlers
ClientPlayNetworking.registerGlobalReceiver(
    ShipTransformSyncS2CPacket.ID,
    (client, handler, buf, responseSender) -> {
        ShipTransformSyncS2CPacket pkt = ShipTransformSyncS2CPacket.read(buf);
        client.execute(() -> ShipTransformClientCache.getInstance().update(pkt.shipId(), pkt.transform()));
    }
);
ClientPlayNetworking.registerGlobalReceiver(
    ShipBoardS2CPacket.ID,
    (client, handler, buf, responseSender) -> {
        ShipBoardS2CPacket pkt = ShipBoardS2CPacket.read(buf);
        client.execute(() -> {
            if (pkt.boarding()) {
                ShipTransformClientCache.getInstance().setBoardedShip(pkt.shipId());
            } else {
                ShipTransformClientCache.getInstance().setBoardedShip(null);
            }
        });
    }
);
```

### 4.4 `ShipTransformClientCache.java`

Create: `src/main/java/net/shasankp000/Client/ShipTransformClientCache.java`

Client singleton holding the last-received transform for each ship and the current boarded ship ID.

```java
package net.shasankp000.Client;

import net.shasankp000.Ship.Transform.ShipTransform;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ShipTransformClientCache {

    private static ShipTransformClientCache INSTANCE = null;
    private final Map<UUID, ShipTransform> transforms = new ConcurrentHashMap<>();
    private UUID boardedShipId = null;

    private ShipTransformClientCache() {}

    public static ShipTransformClientCache getInstance() {
        if (INSTANCE == null) INSTANCE = new ShipTransformClientCache();
        return INSTANCE;
    }

    public void update(UUID shipId, ShipTransform transform) {
        transforms.put(shipId, transform);
    }

    public void remove(UUID shipId) {
        transforms.remove(shipId);
    }

    public Map<UUID, ShipTransform> getAll() {
        return Collections.unmodifiableMap(transforms);
    }

    public ShipTransform get(UUID shipId) {
        return transforms.get(shipId);
    }

    public void setBoardedShip(UUID shipId) { this.boardedShipId = shipId; }
    public UUID getBoardedShipId()          { return boardedShipId; }
    public boolean isAboard()               { return boardedShipId != null; }
}
```

---

## 5. Part 2 Checklist

- [ ] `ShipTransform.java` created
- [ ] `ShipPhysicsEngine.java` rewritten (no entity references)
- [ ] `ShipPhysicsConfig.java` created (renamed from `PhysicsConfig.java`, same constants)
- [ ] `ShipTransformManager.java` created and registered in `AetherialSkies.java`
- [ ] `ShipTransformSyncS2CPacket.java` created
- [ ] `ShipBoardS2CPacket.java` created
- [ ] `ShipTransformClientCache.java` created
- [ ] Packet receivers registered in `AetherialSkiesClient.java`
- [ ] `ShipStructureManager.init()` called on `SERVER_STARTED` in `AetherialSkies.java`
- [ ] Project compiles. `ShipStructureRenderer` (Part 3) will have unresolved refs — expected.
- [ ] **Do not implement Part 3 until Part 2 compiles**
