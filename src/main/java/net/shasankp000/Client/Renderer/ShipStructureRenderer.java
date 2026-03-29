package net.shasankp000.Client.Renderer;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.Client.ShipTransformCache;
import net.shasankp000.Ship.Client.ShipTransformCache.ClientShip;
import net.shasankp000.Ship.ShipCrateService;
import net.shasankp000.Ship.Transform.ShipTransform;

/**
 * Renders every ship in ShipTransformCache as a collection of real block
 * models positioned in overworld space.
 *
 * Hook: WorldRenderEvents.AFTER_ENTITIES
 *
 * For each ship:
 *  1. Push matrix, translate to (worldOffset - cameraPos).
 *  2. Rotate by ship yaw around Y.
 *  3. For each PackedBlock, push/translate by localOffset, render block model, pop.
 *  4. Pop outer matrix.
 *
 * Light is sampled at the ship's overworld worldOffset position so blocks
 * are lit by the real sky / block light at their apparent location.
 */
public final class ShipStructureRenderer {

    private ShipStructureRenderer() {}

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(ShipStructureRenderer::render);
    }

    private static void render(WorldRenderContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || ctx.matrixStack() == null || ctx.consumers() == null) return;

        MatrixStack matrices       = ctx.matrixStack();
        VertexConsumerProvider vcp = ctx.consumers();
        BlockRenderManager brm     = client.getBlockRenderManager();
        Vec3d cameraPos            = ctx.camera().getPos();

        for (ClientShip ship : ShipTransformCache.INSTANCE.getAll()) {
            ShipTransform t = ship.transform;
            if (t == null) continue;

            Vec3d offset = t.worldOffset();

            // Sample light at the overworld block the ship is centred on.
            int light = getLightAt(client, offset);

            matrices.push();

            // Translate from camera-relative origin to ship world-offset.
            matrices.translate(
                offset.x - cameraPos.x,
                offset.y - cameraPos.y,
                offset.z - cameraPos.z
            );

            // Rotate entire ship around its Y axis.
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-t.yaw()));

            for (ShipCrateService.PackedBlock pb : ship.blocks) {
                Vec3d lo = pb.localOffset();

                BlockState state = getState(pb.blockId());
                if (state == null) continue;

                matrices.push();
                // Each block is centred on its local offset; subtract 0.5 so
                // the model origin (bottom-west-north corner) lands correctly.
                matrices.translate(lo.x - 0.5, lo.y, lo.z - 0.5);

                brm.renderBlockAsEntity(state, matrices, vcp, light, OverlayTexture.DEFAULT_UV);

                matrices.pop();
            }

            matrices.pop();
        }
    }

    // ---- Helpers ---------------------------------------------------------

    private static BlockState getState(String blockId) {
        try {
            return Registries.BLOCK.get(new Identifier(blockId)).getDefaultState();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns packed sky+block light for the block position closest to the
     * ship's world offset. Falls back to full-bright if the world is null.
     */
    private static int getLightAt(MinecraftClient client, Vec3d pos) {
        if (client.world == null) return LightmapTextureManager.MAX_LIGHT_COORDINATE;
        net.minecraft.util.math.BlockPos bp = net.minecraft.util.math.BlockPos.ofFloored(pos);
        return client.world.getLightLevel(net.minecraft.world.LightType.BLOCK, bp) << 4
             | client.world.getLightLevel(net.minecraft.world.LightType.SKY,   bp) << 20;
    }
}
