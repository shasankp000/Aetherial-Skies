package net.shasankp000;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.shasankp000.Client.Renderer.GravityBlockEntityRenderer;
import net.shasankp000.Client.Renderer.ShipBoatEntityRenderer;
import net.shasankp000.Client.Renderer.ShipSelectionRenderer;
import net.shasankp000.Registry.ModEntityTypes;

public class AetherialSkiesClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Register your custom entity renderer.
        EntityRendererRegistry.register(
                ModEntityTypes.GRAVITY_BLOCK_ENTITY,
                context -> new GravityBlockEntityRenderer(context)
        );
        EntityRendererRegistry.register(
                ModEntityTypes.SHIP_BOAT_ENTITY,
                ShipBoatEntityRenderer::new
        );
        ShipSelectionRenderer.register();
    }
}
