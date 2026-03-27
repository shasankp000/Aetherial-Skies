# Ship Architecture — Part 3: Client-Side Block Renderer

> **For GitHub Copilot:** This is Part 3 of 4. Complete Parts 1 and 2 first. This part covers the client render pass that draws ship blocks at their transform-offset positions. After this part, ships should be visually rendering in the correct world-space position.

---

## 0. How the Render Pass Works

Minecraft renders the world from the camera's perspective. When we want blocks to appear at a different position than they exist in the world, we:

1. Hook into `WorldRenderEvents.AFTER_ENTITIES` (after normal world render, before translucent)
2. For each active ship transform:
   a. Compute the render-space offset: `worldOffset - cameraPos`
   b. Push a GL matrix: translate by offset, rotate by yaw around ship centre
   c. Iterate every block in the structure
   d. Render each block's model at its local position using `BlockRenderManager`
   e. Pop the matrix

Blocks are rendered at their *visual* position. Their *actual* position is in the storage dimension and never changes.

---

## 1. `ShipStructureRenderer.java`

Create: `src/main/java/net/shasankp000/Client/Render/ShipStructureRenderer.java`

```java
package net.shasankp000.Client.Render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Client.ShipTransformClientCache;
import net.shasankp000.Ship.ShipCrateService;
import net.shasankp000.Ship.ShipHullData;
import net.shasankp000.Ship.Transform.ShipTransform;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class ShipStructureRenderer {

    private ShipStructureRenderer() {}

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(ShipStructureRenderer::render);
    }

    private static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;

        ShipTransformClientCache cache = ShipTransformClientCache.getInstance();
        Map<UUID, ShipTransform> allTransforms = cache.getAll();
        if (allTransforms.isEmpty()) return;

        MatrixStack matrices = ctx.matrixStack();
        Camera camera = ctx.camera();
        Vec3d camPos = camera.getPos();

        BlockRenderManager blockRenderer = mc.getBlockRenderManager();
        VertexConsumerProvider.Immediate immediate =
            mc.getBufferBuilders().getEntityVertexConsumers();

        for (Map.Entry<UUID, ShipTransform> entry : allTransforms.entrySet()) {
            ShipTransform transform = entry.getValue();
            renderShip(matrices, camPos, blockRenderer, immediate, transform, mc);
        }

        immediate.draw();
    }

    private static void renderShip(
        MatrixStack matrices,
        Vec3d camPos,
        BlockRenderManager blockRenderer,
        VertexConsumerProvider.Immediate immediate,
        ShipTransform transform,
        MinecraftClient mc
    ) {
        // The ship structure centre in world space
        Vec3d worldCentre = transform.worldOffset();

        // Offset from camera to ship centre (render space)
        double dx = worldCentre.x - camPos.x;
        double dy = worldCentre.y - camPos.y;
        double dz = worldCentre.z - camPos.z;

        // We need hull data to know which blocks to render.
        // Hull data is stored in ShipTransformClientCache alongside the transform.
        // (See Section 2 below for how hull data reaches the client cache.)
        ShipHullData hullData = ShipTransformClientCache.getInstance().getHullData(entry.getKey());
        if (hullData == null) return;

        matrices.push();

        // Translate to ship centre in render space
        matrices.translate(dx, dy, dz);

        // Rotate around Y axis by ship yaw
        matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y
            .rotationDegrees(-transform.yaw()));

        for (ShipCrateService.PackedBlock pb : hullData.blocks()) {
            Vec3d local = pb.localOffset();
            BlockState state = Registries.BLOCK
                .get(new Identifier(pb.blockId()))
                .getDefaultState();

            if (state.getRenderType() == BlockRenderType.INVISIBLE) continue;

            matrices.push();
            // Each block is offset from ship centre by its local position
            // Subtract 0.5 on each axis so block centre aligns with local position
            matrices.translate(
                local.x - transform.structureOrigin().x + hullData.computeBounds().minX(),
                local.y - transform.structureOrigin().y + hullData.computeBounds().minY(),
                local.z - transform.structureOrigin().z + hullData.computeBounds().minZ()
            );

            // Sample light from the rendered world position
            BlockPos lightPos = new BlockPos(
                (int)(worldCentre.x + local.x),
                (int)(worldCentre.y + local.y),
                (int)(worldCentre.z + local.z)
            );
            int light = WorldRenderer.getLightmapCoordinates(mc.world, lightPos);

            VertexConsumer consumer = immediate.getBuffer(
                RenderLayers.getBlockLayer(state)
            );
            blockRenderer.renderBlock(
                state,
                new BlockPos(0, 0, 0),
                mc.world,
                matrices,
                consumer,
                false,
                mc.world.random
            );

            matrices.pop();
        }

        matrices.pop();
    }
}
```

---

## 2. Sending Hull Data to the Client

The renderer needs `ShipHullData` (which blocks to draw) on the client side. This is sent once when the ship is deployed, not every tick.

### 2.1 `ShipDeployS2CPacket.java`

Create: `src/main/java/net/shasankp000/Ship/Packet/ShipDeployS2CPacket.java`

Sent once when a ship is first deployed. Carries the hull data (block list + physics params) and the initial transform.

```java
package net.shasankp000.Ship.Packet;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.shasankp000.Ship.ShipHullData;
import net.shasankp000.Ship.Transform.ShipTransform;

import java.util.UUID;

public record ShipDeployS2CPacket(UUID shipId, ShipHullData hullData, ShipTransform initialTransform) {

    public static final Identifier ID =
        new Identifier("aetherial-skies", "ship_deploy");

    /** Broadcasts to all players in the overworld. */
    public void broadcast(ServerWorld overworld) {
        PacketByteBuf buf = encode();
        for (var player : PlayerLookup.world(overworld)) {
            ServerPlayNetworking.send(player, ID, buf);
        }
    }

    public PacketByteBuf encode() {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(shipId);
        buf.writeNbt(hullData.toEntityTag());
        buf.writeDouble(initialTransform.structureOrigin().x);
        buf.writeDouble(initialTransform.structureOrigin().y);
        buf.writeDouble(initialTransform.structureOrigin().z);
        buf.writeDouble(initialTransform.worldOffset().x);
        buf.writeDouble(initialTransform.worldOffset().y);
        buf.writeDouble(initialTransform.worldOffset().z);
        buf.writeFloat(initialTransform.yaw());
        return buf;
    }

    public static ShipDeployS2CPacket decode(PacketByteBuf buf) {
        UUID shipId        = buf.readUuid();
        NbtCompound nbt    = buf.readNbt();
        ShipHullData hull  = ShipHullData.fromEntityTag(nbt);
        double sox = buf.readDouble(), soy = buf.readDouble(), soz = buf.readDouble();
        double wox = buf.readDouble(), woy = buf.readDouble(), woz = buf.readDouble();
        float yaw  = buf.readFloat();
        ShipTransform t = new ShipTransform(
            new net.minecraft.util.math.Vec3d(sox, soy, soz),
            new net.minecraft.util.math.Vec3d(wox, woy, woz),
            yaw,
            net.minecraft.util.math.Vec3d.ZERO
        );
        return new ShipDeployS2CPacket(shipId, hull, t);
    }
}
```

### 2.2 Update `ShipTransformClientCache` to Store Hull Data

Add to `ShipTransformClientCache.java`:

```java
// Add field:
private final Map<UUID, ShipHullData> hullDataMap = new ConcurrentHashMap<>();

// Add methods:
public void registerShip(UUID shipId, ShipHullData hullData, ShipTransform initialTransform) {
    hullDataMap.put(shipId, hullData);
    transforms.put(shipId, initialTransform);
}

public ShipHullData getHullData(UUID shipId) {
    return hullDataMap.get(shipId);
}

public void unregisterShip(UUID shipId) {
    hullDataMap.remove(shipId);
    transforms.remove(shipId);
}
```

### 2.3 Register `ShipDeployS2CPacket` Receiver in `AetherialSkiesClient.java`

```java
ClientPlayNetworking.registerGlobalReceiver(
    ShipDeployS2CPacket.ID,
    (client, handler, buf, responseSender) -> {
        ShipDeployS2CPacket pkt = ShipDeployS2CPacket.decode(buf);
        client.execute(() -> ShipTransformClientCache.getInstance()
            .registerShip(pkt.shipId(), pkt.hullData(), pkt.initialTransform()));
    }
);
```

### 2.4 Broadcast `ShipDeployS2CPacket` from `ShipStructureManager.deploy()`

At the end of `ShipStructureManager.deploy()`, add:
```java
new ShipDeployS2CPacket(shipId, hullData, initialTransform)
    .broadcast(server.getOverworld());
```

(This requires `server` to be set — it is set in `init()`.)

---

## 3. Register the Renderer

In `AetherialSkiesClient.java` `onInitializeClient()`:

```java
ShipStructureRenderer.register();
```

---

## 4. Part 3 Checklist

- [ ] `ShipStructureRenderer.java` created and registered in `AetherialSkiesClient`
- [ ] `ShipDeployS2CPacket.java` created
- [ ] `ShipTransformClientCache` updated with `registerShip`, `getHullData`, `unregisterShip`
- [ ] `ShipDeployS2CPacket` receiver registered in `AetherialSkiesClient`
- [ ] `ShipStructureManager.deploy()` broadcasts `ShipDeployS2CPacket`
- [ ] `ShipStructureRenderer` uses `getHullData()` from cache
- [ ] Project compiles
- [ ] **In-game test (creative mode):** deploy a ship crate → ship blocks visible at waterline, rotating with correct yaw
- [ ] **Do not implement Part 4 until Part 3 renders correctly**
