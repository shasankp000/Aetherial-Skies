package net.shasankp000;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.shasankp000.Client.Renderer.GravityBlockEntityRenderer;
import net.shasankp000.Client.Renderer.ShipSelectionRenderer;
import net.shasankp000.Client.Renderer.ShipStructureRenderer;
import net.shasankp000.Registry.ModEntityTypes;
import net.shasankp000.Ship.Client.ShipTransformCache;
import net.shasankp000.Ship.Network.ShipDeployS2CPacket;
import net.shasankp000.Ship.Network.ShipPilotEnteredS2CPacket;
import net.shasankp000.Ship.Network.ShipPilotExitedS2CPacket;
import net.shasankp000.Ship.Network.ShipRemoveS2CPacket;
import net.shasankp000.Ship.Network.ShipSteerC2SPacket;
import net.shasankp000.Ship.Network.ShipTransformSyncS2CPacket;

import java.util.UUID;

public class AetherialSkiesClient implements ClientModInitializer {

    /**
     * The shipId the local player is currently piloting, or null if not piloting.
     * Written by S2C packet handlers (Netty thread → execute()), read each tick.
     */
    public static volatile UUID pilotingShipId = null;

    @Override
    public void onInitializeClient() {
        // Entity renderers.
        EntityRendererRegistry.register(
                ModEntityTypes.GRAVITY_BLOCK_ENTITY,
                GravityBlockEntityRenderer::new
        );
        ShipSelectionRenderer.register();
        ShipStructureRenderer.register();

        // ---- Ship network packet receivers --------------------------------

        ClientPlayNetworking.registerGlobalReceiver(
            ShipDeployS2CPacket.ID,
            (client, handler, buf, responseSender) -> {
                ShipDeployS2CPacket.Payload p = ShipDeployS2CPacket.decode(buf);
                client.execute(() -> ShipTransformCache.INSTANCE.onDeploy(p));
            }
        );

        ClientPlayNetworking.registerGlobalReceiver(
            ShipTransformSyncS2CPacket.ID,
            (client, handler, buf, responseSender) -> {
                ShipTransformSyncS2CPacket.Payload p = ShipTransformSyncS2CPacket.decode(buf);
                client.execute(() -> ShipTransformCache.INSTANCE.onTransformSync(
                    p.shipId(), p.worldOffset(), p.yaw(), p.velocity()));
            }
        );

        ClientPlayNetworking.registerGlobalReceiver(
            ShipRemoveS2CPacket.ID,
            (client, handler, buf, responseSender) -> {
                UUID id = ShipRemoveS2CPacket.decode(buf);
                client.execute(() -> ShipTransformCache.INSTANCE.onRemove(id));
            }
        );

        // Pilot entered: store the shipId so the tick handler can send inputs.
        ClientPlayNetworking.registerGlobalReceiver(
            ShipPilotEnteredS2CPacket.ID,
            (client, handler, buf, responseSender) -> {
                UUID shipId = ShipPilotEnteredS2CPacket.decode(buf);
                client.execute(() -> {
                    pilotingShipId = shipId;
                    AetherialSkies.LOGGER.info("[Client] Entered pilot mode for ship {}",
                        shipId.toString().substring(0, 8));
                });
            }
        );

        // Pilot exited: clear.
        ClientPlayNetworking.registerGlobalReceiver(
            ShipPilotExitedS2CPacket.ID,
            (client, handler, buf, responseSender) -> {
                ShipPilotExitedS2CPacket.decode(buf);
                client.execute(() -> {
                    pilotingShipId = null;
                    AetherialSkies.LOGGER.info("[Client] Exited pilot mode");
                });
            }
        );

        ClientPlayConnectionEvents.DISCONNECT.register(
            (handler, client) -> {
                ShipTransformCache.INSTANCE.clear();
                pilotingShipId = null;
            }
        );

        // ---- Per-tick steer input sender ----------------------------------
        //
        // Each client tick while piloting:
        //  1. Read W/S state -> forward
        //  2. Read A/D state -> turn
        //  3. Send ShipSteerC2SPacket
        //  4. Sync local player yaw to the ship's current yaw (camera sync).
        //
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            UUID shipId = pilotingShipId;
            if (shipId == null) return;

            ClientPlayerEntity player = client.player;
            if (player == null) return;

            KeyBinding forward  = client.options.forwardKey;   // W
            KeyBinding backward = client.options.backKey;      // S
            KeyBinding left     = client.options.leftKey;      // A
            KeyBinding right    = client.options.rightKey;     // D

            int fwd  = (forward.isPressed()  ? 1 : 0) - (backward.isPressed() ? 1 : 0);
            int turn = (right.isPressed()    ? 1 : 0) - (left.isPressed()     ? 1 : 0);

            ShipSteerC2SPacket.send(fwd, turn);

            // Camera sync: set the player's yaw to match the ship's current yaw.
            // The server authoritative yaw comes back via ShipTransformSyncS2CPacket
            // and is stored in ShipTransformCache each tick.
            ShipTransformCache.ClientShip ship = ShipTransformCache.INSTANCE.get(shipId);
            if (ship != null) {
                float shipYaw = ship.transform.yaw();
                // headYaw and bodyYaw must both be set, otherwise the neck twists.
                player.setYaw(shipYaw);
                player.headYaw = shipYaw;
                player.bodyYaw = shipYaw;
                // prevYaw fields smooth the interpolation between ticks.
                player.prevYaw     = shipYaw;
                player.prevHeadYaw = shipYaw;
                player.prevBodyYaw = shipYaw;
            }
        });
    }
}
