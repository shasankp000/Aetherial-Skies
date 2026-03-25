package net.shasankp000.Client.Renderer;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.ShipSelectionManager;

import java.util.Optional;

public final class ShipSelectionRenderer {
    private ShipSelectionRenderer() {
    }

    public static void register() {
        WorldRenderEvents.LAST.register(ShipSelectionRenderer::renderSelectionOutline);
    }

    private static void renderSelectionOutline(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || context.matrixStack() == null || context.consumers() == null || context.camera() == null) {
            return;
        }

        Optional<Box> selectionBox = ShipSelectionManager.getRenderBox(client.player.getUuid());
        if (selectionBox.isEmpty()) {
            return;
        }

        MatrixStack matrices = context.matrixStack();
        Vec3d cameraPos = context.camera().getPos();

        matrices.push();
        matrices.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        VertexConsumer lineBuffer = context.consumers().getBuffer(RenderLayer.getLines());
        WorldRenderer.drawBox(matrices, lineBuffer, selectionBox.get().expand(0.002D), 0.15f, 0.85f, 1.0f, 1.0f);

        matrices.pop();
    }
}
