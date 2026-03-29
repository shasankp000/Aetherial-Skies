package net.shasankp000;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.shasankp000.Client.Renderer.GravityBlockEntityRenderer;
import net.shasankp000.Client.Renderer.ShipSelectionRenderer;
import net.shasankp000.Registry.ModEntityTypes;

/**
 * Client entry point.
 *
 * ShipBoatEntityRenderer has been removed — ships are rendered via
 * ShipStructureRenderer (added in Part 3 of the new architecture).
 */
public class AetherialSkiesClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(
                ModEntityTypes.GRAVITY_BLOCK_ENTITY,
                GravityBlockEntityRenderer::new
        );
        ShipSelectionRenderer.register();
    }
}
