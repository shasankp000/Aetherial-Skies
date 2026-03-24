package net.shasankp000.Client.Renderer;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.*;
import net.minecraft.client.render.block.BlockModelRenderer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.shasankp000.Entity.GravityBlockEntity;
import net.shasankp000.Gravity.GravityData;
import net.shasankp000.Util.BlockStateRegistry;

public class GravityBlockEntityRenderer extends EntityRenderer<GravityBlockEntity> {

    // Store the BlockRenderManager from the context.
    private final BlockRenderManager blockRenderManager;
    private static final float MINING_THRESHOLD = 1.0f;
    public GravityBlockEntityRenderer(EntityRendererFactory.Context context) {
        super(context);
        this.shadowRadius = 0.5f;
        this.blockRenderManager = context.getBlockRenderManager();
    }

    @Override
    public void render(GravityBlockEntity entity, float entityYaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        String blockIdString = entity.getBlockIdString();
        int timerState = entity.getLandingTimer();
        float currentRotation = entity.getCurrentRotation();
        float previousRotation = entity.getPreviousRotation();
        float roll = entity.getRoll();
        float miningProgress = entity.getMiningProgress();
        float verticalSpeed = entity.getTrackedVerticalSpeed();
        float horizontalSpeed = entity.getTrackedHorizontalSpeed();
        float impactAmplitude = entity.getTrackedImpactAmplitude();
        float settleProgress = entity.getTrackedSettleProgress();
        float age = entity.age + tickDelta;
        float renderYaw = entity.getYaw();
        float renderPitch = entity.getPitch();
        BlockPos renderPos = entity.getBlockPos();

        BlockState blockState = entity.getBlockState();
        if (blockState == null) {
            blockState = BlockStateRegistry.getDefaultStateFor(blockIdString);
        }

        float blockWeight = GravityData.getProfile(blockState.getBlock()).mass();
        float interpolatedRotation = MathHelper.lerp(tickDelta, previousRotation, currentRotation);

//        System.out.println("Rendering entity: " + entity.getName().getString() + "with block state: " + state.blockState);

        matrices.push();

        // Translate so that the block model is centered.
        matrices.translate(-0.5, 0.0, -0.5);

        // Apply rotation using the interpolated angle.

        // Compute interpolated rotation.

        float weightFactor = MathHelper.clamp(1.0f / Math.max(blockWeight, 0.75f), 0.35f, 1.2f);
        float airborneFactor = timerState > 0 ? 0.0f : 1.0f;

        // Airborne tilt follows motion; on impact we switch to damped wobble as the block settles.
        float forwardTilt = MathHelper.clamp(-verticalSpeed * 6.0f, -14.0f, 14.0f) * airborneFactor * weightFactor;
        float lateralTilt = MathHelper.clamp(horizontalSpeed * 26.0f, 0.0f, 11.0f) * airborneFactor * weightFactor;

        float wobbleDecay = 1.0f - settleProgress;
        float wobbleWave = (float) Math.sin((age * 0.8f) + (interpolatedRotation * 0.12f));
        float impactTilt = wobbleWave * 8.0f * impactAmplitude * wobbleDecay * weightFactor;
        float impactLift = Math.abs(wobbleWave) * 0.05f * impactAmplitude * wobbleDecay;

        float squash = 1.0f - (0.08f * impactAmplitude * wobbleDecay);
        float stretch = 1.0f + (0.08f * impactAmplitude * wobbleDecay);

        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(renderYaw + (interpolatedRotation * 0.15f)));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(renderPitch + forwardTilt + impactTilt));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(roll + lateralTilt - (impactTilt * 0.5f)));



        BlockState stateToRender = blockState;
//        System.out.println("Rendering block state: " + stateToRender + " (RenderType: " + stateToRender.getRenderType() + ")");


        if (stateToRender == null) {
            matrices.pop();
            return;
        }

        // Compute a vertical offset so that the model's bottom stays on the ground.
        double blockHeight = 1.0;
        double pitchRad = Math.toRadians(Math.abs(renderPitch));
        double verticalOffset = blockHeight * Math.sin(pitchRad);
        // Move upward by the offset.
        matrices.translate(0, verticalOffset + impactLift, 0);

        if (timerState > 0) {
            // Keep the compressed model visually grounded while scaling during impact.
            matrices.translate(0.0, (1.0f - squash) * 0.5f, 0.0);
            matrices.scale(stretch, squash, stretch);
        }


        BlockPos pos = renderPos;
        var world = entity.getWorld();
        var model = this.blockRenderManager.getModel(stateToRender);
        int overlay = OverlayTexture.DEFAULT_UV;

        // Use the "entity" block render path to avoid world-face AO shading artifacts
        // when the block is rendered with arbitrary yaw/pitch/roll.
        this.blockRenderManager.renderBlockAsEntity(stateToRender, matrices, vertexConsumers, light, overlay);

        BlockModelRenderer renderer = this.blockRenderManager.getModelRenderer();
        boolean cull = false;
        var random = world.getRandom();
        long seed = stateToRender.getRenderingSeed(pos);

        float normalizedProgress = MathHelper.clamp(miningProgress / MINING_THRESHOLD, 0.0f, 1.0f);
        int stage = MathHelper.clamp((int) (normalizedProgress * 10.0f) - 1, -1, 9);
        if (stage >= 0) {
            Identifier crackTexture = new Identifier("minecraft", "textures/block/destroy_stage_" + stage + ".png");
            VertexConsumer crackConsumer = vertexConsumers.getBuffer(RenderLayer.getBlockBreaking(crackTexture));
            VertexConsumer overlayConsumer = new OverlayVertexConsumer(
                    crackConsumer,
                    matrices.peek().getPositionMatrix(),
                    matrices.peek().getNormalMatrix(),
                    1.0f
            );
            renderer.render(
                    world,
                    model,
                    stateToRender,
                    pos,
                    matrices,
                    overlayConsumer,
                    cull,
                    random,
                    seed,
                    overlay
            );
        }

        matrices.pop();
    }


    public Identifier getTexture(GravityBlockEntity entity) {
        // This method is not really used since we're rendering a block state,
        // but you must return something. We'll return a fallback texture.
        return new Identifier("minecraft", "textures/block/stone.png");
    }

//    private void renderCrackOverlay(MatrixStack matrices, int light, Identifier texture) {
//        // Bind the crack texture.
//        // This call depends on your mappings; in Yarn you might use RenderSystem:
//        RenderSystem.setShaderTexture(0, texture);
//
//        // Get the current transformation matrix.
//        MatrixStack.Entry entry = matrices.peek();
//        Matrix4f matrix = entry.getPositionMatrix();
//
//        // Get the Tessellator instance and its BufferBuilder.
//        Tessellator tessellator = Tessellator.getInstance();
//        BufferBuilder buffer = tessellator.getBuffer();
//
//        // Begin drawing a quad.
//        // The vertex format used here is POSITION_COLOR_TEXTURE_LIGHT;
//        // if your mapping is different, adjust accordingly.
//        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT);
//
//        // Define a full-quad overlay for the top face of the block.
//        // The coordinates here are relative to the block's origin.
//        buffer.vertex(matrix, 0.0F, 1.0F, 0.0F)
//                .color(255, 255, 255, 200) // RGBA; adjust alpha for transparency.
//                .texture(0.0F, 0.0F)
//                .light(light)
//                .next();
//        buffer.vertex(matrix, 1.0F, 1.0F, 0.0F)
//                .color(255, 255, 255, 200)
//                .texture(1.0F, 0.0F)
//                .light(light)
//                .next();
//        buffer.vertex(matrix, 1.0F, 1.0F, 1.0F)
//                .color(255, 255, 255, 200)
//                .texture(1.0F, 1.0F)
//                .light(light)
//                .next();
//        buffer.vertex(matrix, 0.0F, 1.0F, 1.0F)
//                .color(255, 255, 255, 200)
//                .texture(0.0F, 1.0F)
//                .light(light)
//                .next();
//
//        tessellator.draw();
//    }

}
