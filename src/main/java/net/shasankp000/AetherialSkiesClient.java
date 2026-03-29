package net.shasankp000;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.shasankp000.Client.Renderer.GravityBlockEntityRenderer;
import net.shasankp000.Client.Renderer.ShipSelectionRenderer;
import net.shasankp000.Client.Renderer.ShipStructureRenderer;
import net.shasankp000.Registry.ModEntityTypes;
import net.shasankp000.Ship.Client.ShipTransformCache;
import net.shasankp000.Ship.Network.ShipDeployS2CPacket;
import net.shasankp000.Ship.Network.ShipRemoveS2CPacket;
import net.shasankp000.Ship.Network.ShipTransformSyncS2CPacket;

import java.util.UUID;

public class AetherialSkiesClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Entity renderers.
        EntityRendererRegistry.register(
                ModEntityTypes.GRAVITY_BLOCK_ENTITY,
                GravityBlockEntityRenderer::new
        );
        ShipSelectionRenderer.register();

        // Ship structure block-overlay renderer.
        ShipStructureRenderer.register();

        // ---- Ship network packet receivers --------------------------------

        // Full hull data sent once on deploy.
        ClientPlayNetworking.registerGlobalReceiver(
            ShipDeployS2CPacket.ID,
            (client, handler, buf, responseSender) -> {
                ShipDeployS2CPacket.Payload p = ShipDeployS2CPacket.decode(buf);
                client.execute(() -> ShipTransformCache.INSTANCE.onDeploy(p));
            }
        );

        // Per-tick transform update.
        ClientPlayNetworking.registerGlobalReceiver(
            ShipTransformSyncS2CPacket.ID,
            (client, handler, buf, responseSender) -> {
                ShipTransformSyncS2CPacket.Payload p = ShipTransformSyncS2CPacket.decode(buf);
                client.execute(() -> ShipTransformCache.INSTANCE.onTransformSync(
                    p.shipId(), p.worldOffset(), p.yaw(), p.velocity()));
            }
        );

        // Ship removed / destroyed.
        ClientPlayNetworking.registerGlobalReceiver(
            ShipRemoveS2CPacket.ID,
            (client, handler, buf, responseSender) -> {
                UUID id = ShipRemoveS2CPacket.decode(buf);
                client.execute(() -> ShipTransformCache.INSTANCE.onRemove(id));
            }
        );

        // Clear cache on disconnect / world change.
        ClientPlayConnectionEvents.DISCONNECT.register(
            (handler, client) -> ShipTransformCache.INSTANCE.clear()
        );
    }
}
