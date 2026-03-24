package net.shasankp000;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.shasankp000.Client.Renderer.GravityBlockEntityRenderer;

public class AetherialSkiesClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Register your custom entity renderer.
        EntityRendererRegistry.register(
                AetherialSkies.GRAVITY_BLOCK_ENTITY,
                context -> new GravityBlockEntityRenderer(context)
        );
    }
}