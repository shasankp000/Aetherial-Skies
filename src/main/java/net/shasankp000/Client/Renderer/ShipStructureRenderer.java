package net.shasankp000.Client.Renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.shasankp000.AetherialSkies;
import net.shasankp000.Ship.Client.ShipTransformCache;
import net.shasankp000.Ship.Client.ShipTransformCache.ClientShip;
import net.shasankp000.Ship.ShipCrateService;
import net.shasankp000.Ship.Transform.ShipTransform;

/**
 * Renders every ship in ShipTransformCache as a collection of real block
 * models positioned in overworld space.
 *
 * Hook: WorldRenderEvents.LAST
 *   - MatrixStack is still valid and in camera space.
 *   - ctx.consumers() is documented as null at AFTER_ENTITIES in Fabric
 *     0.92.x (all buffered quads are flushed before that event). LAST
 *     fires after translucent sorting but before the frame is presented,
 *     making it the correct place for manual Tessellator-backed rendering.
 *
 * VCP: we create our own VertexConsumerProvider.Immediate backed by
 *   Tessellator.getInstance() and flush it after each ship. This is the
 *   same pattern block-entity renderers use internally.
 *
 * Lightmap: use LightmapTextureManager.pack(block, sky) which produces
 *   the correct (sky << 16) | block packing for 1.20.1.
 */
public final class ShipStructureRenderer {

    private ShipStructureRenderer() {}

    private static boolean loggedOnce = false;

    public static void register() {
        WorldRenderEvents.LAST.register(ShipStructureRenderer::render);
    }

    private static void render(WorldRenderContext ctx) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || ctx.matrixStack() == null) return;

        var ships = ShipTransformCache.INSTANCE.getAll();
        if (ships.isEmpty()) return;

        if (!loggedOnce) {
            AetherialSkies.LOGGER.info("[ShipStructureRenderer] Renderer executing, {} ship(s) in cache.",
                ships.size());
            loggedOnce = true;
        }

        MatrixStack      matrices = ctx.matrixStack();
        BlockRenderManager brm   = client.getBlockRenderManager();
        Vec3d cameraPos           = ctx.camera().getPos();
        Tessellator tessellator   = Tessellator.getInstance();

        // Build our own immediate VCP backed by the Tessellator.
        // RenderLayer.getSolid() uses TRANSLUCENT_MOVING_BLOCK internally
        // but for block-as-entity rendering we need a multi-phase buffer.
        VertexConsumerProvider.Immediate vcp =
            VertexConsumerProvider.immediate(tessellator.getBuffer());

        RenderSystem.enableDepthTest();

        for (ClientShip ship : ships) {
            ShipTransform t = ship.transform;
            if (t == null || ship.blocks == null || ship.blocks.isEmpty()) continue;

            Vec3d offset = t.worldOffset();
            int light    = getLightAt(client, offset);

            matrices.push();
            matrices.translate(
                offset.x - cameraPos.x,
                offset.y - cameraPos.y,
                offset.z - cameraPos.z
            );
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-t.yaw()));

            for (ShipCrateService.PackedBlock pb : ship.blocks) {
                BlockState state = resolveState(pb.blockId());
                if (state == null || state.isAir()) continue;

                Vec3d lo = pb.localOffset();
                matrices.push();
                // localOffset is the block's centre in local space;
                // subtract 0.5 on X/Z so the model NW corner aligns correctly.
                // Y is the bottom face already, no adjustment needed.
                matrices.translate(lo.x - 0.5, lo.y, lo.z - 0.5);
                brm.renderBlockAsEntity(state, matrices, vcp, light, OverlayTexture.DEFAULT_UV);
                matrices.pop();
            }

            // Flush after each ship so draw calls don't accumulate unboundedly.
            vcp.draw();
            matrices.pop();
        }
    }

    // ---- Helpers ---------------------------------------------------------

    private static BlockState resolveState(String blockId) {
        try {
            BlockState state = Registries.BLOCK
                .get(new Identifier(blockId))
                .getDefaultState();
            // Registries.BLOCK.get() returns AIR for unknown IDs;
            // treat that as a missing block (no render) rather than
            // rendering an invisible air block.
            if (state.isOf(Blocks.AIR)) return null;
            return state;
        } catch (Exception e) {
            AetherialSkies.LOGGER.warn("[ShipStructureRenderer] Unknown block id: {}", blockId);
            return null;
        }
    }

    /**
     * Correct 1.20.1 lightmap packing.
     * LightmapTextureManager.pack(block, sky) = (sky << 16) | (block << 0).
     * Falls back to full-bright so blocks are always visible even in caves.
     */
    private static int getLightAt(MinecraftClient client, Vec3d pos) {
        if (client.world == null) return LightmapTextureManager.MAX_LIGHT_COORDINATE;
        BlockPos bp = BlockPos.ofFloored(pos);
        int block = client.world.getLightLevel(LightType.BLOCK, bp);
        int sky   = client.world.getLightLevel(LightType.SKY,   bp);
        return LightmapTextureManager.pack(block, sky);
    }
}
