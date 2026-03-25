package net.shasankp000.Client.Renderer;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.shasankp000.Entity.ShipBoatEntity;
import net.shasankp000.Ship.ShipCrateService;
import net.shasankp000.Ship.ShipHullData;

public class ShipBoatEntityRenderer extends EntityRenderer<ShipBoatEntity> {
    private final BlockRenderManager blockRenderManager;

    public ShipBoatEntityRenderer(EntityRendererFactory.Context context) {
        super(context);
        this.blockRenderManager = context.getBlockRenderManager();
        this.shadowRadius = 1.2f;
    }

    @Override
    public void render(ShipBoatEntity entity, float entityYaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        ShipHullData hullData = entity.getHullData();
        if (hullData.blocks().isEmpty()) {
            return;
        }

        matrices.push();
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-entityYaw));

        for (ShipCrateService.PackedBlock packedBlock : hullData.blocks()) {
            Identifier blockId = Identifier.tryParse(packedBlock.blockId());
            if (blockId == null || !Registries.BLOCK.containsId(blockId)) {
                continue;
            }

            BlockState state = Registries.BLOCK.get(blockId).getDefaultState();
            matrices.push();
            matrices.translate(
                    packedBlock.localOffset().x - 0.5D,
                    packedBlock.localOffset().y,
                    packedBlock.localOffset().z - 0.5D
            );
            this.blockRenderManager.renderBlockAsEntity(
                    state,
                    matrices,
                    vertexConsumers,
                    light,
                    OverlayTexture.DEFAULT_UV
            );
            matrices.pop();
        }

        matrices.pop();
    }

    @Override
    public Identifier getTexture(ShipBoatEntity entity) {
        return Identifier.of("minecraft", "textures/block/oak_planks.png");
    }
}
