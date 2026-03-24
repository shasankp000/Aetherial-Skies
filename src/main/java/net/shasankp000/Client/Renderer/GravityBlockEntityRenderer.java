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

public class GravityBlockEntityRenderer extends EntityRenderer<GravityBlockEntity, GravityBlockRenderState> {

    // Store the BlockRenderManager from the context.
    private final BlockRenderManager blockRenderManager;
    private static final float MINING_THRESHOLD = 1.0f;
    public GravityBlockEntityRenderer(EntityRendererFactory.Context context) {
        super(context);
        this.shadowRadius = 0.5f;
        this.blockRenderManager = context.getBlockRenderManager();
    }

    @Override
    public GravityBlockRenderState createRenderState() {
        return new GravityBlockRenderState();
    }

    @Override
    public void updateRenderState(GravityBlockEntity entity, GravityBlockRenderState state, float tickDelta) {
        // Populate our custom render state with the entity and tickDelta.
        state.entity = entity;
        state.tickDelta = tickDelta;
        state.blockIdString = entity.getBlockIdString();
        state.timerState = entity.getLandingTimer();

//        System.out.println("tickDelta: " + tickDelta);

        // Retrieve rotation values from DataTracker:
        state.currentRotation = entity.getCurrentRotation();
        state.previousRotation = entity.getPreviousRotation();

        state.roll = entity.getRoll();
        state.miningProgress = entity.getMiningProgress();
        state.verticalSpeed = entity.getTrackedVerticalSpeed();
        state.horizontalSpeed = entity.getTrackedHorizontalSpeed();
        state.impactAmplitude = entity.getTrackedImpactAmplitude();
        state.settleProgress = entity.getTrackedSettleProgress();
        state.age = entity.age + tickDelta;
        state.renderYaw = entity.getYaw();
        state.renderPitch = entity.getPitch();
        state.renderPos = entity.getBlockPos();


//        System.out.println("State's blockIdString: " + state.blockIdString);

        // Use the entity's block state if available, otherwise use fallback:
        BlockState bs = entity.getBlockState();
        if (bs == null) {
            // Fallback: look up using the stored registry name
            bs = BlockStateRegistry.getDefaultStateFor(state.blockIdString);
        }
        state.blockState = bs;
        state.blockWeight = GravityData.getProfile(bs.getBlock()).mass();

        state.interpolatedRotation = MathHelper.lerp(tickDelta, state.previousRotation, state.currentRotation);

    }

    @Override
    public void render(GravityBlockRenderState state, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        GravityBlockEntity entity = state.entity;

//        System.out.println("Rendering entity: " + entity.getName().getString() + "with block state: " + state.blockState);

        matrices.push();

        // Translate so that the block model is centered.
        matrices.translate(-0.5, 0.0, -0.5);

        // Apply rotation using the interpolated angle.

        // Compute interpolated rotation.

        float weightFactor = MathHelper.clamp(1.0f / Math.max(state.blockWeight, 0.75f), 0.35f, 1.2f);
        float airborneFactor = state.timerState > 0 ? 0.0f : 1.0f;

        // Airborne tilt follows motion; on impact we switch to damped wobble as the block settles.
        float forwardTilt = MathHelper.clamp(-state.verticalSpeed * 6.0f, -14.0f, 14.0f) * airborneFactor * weightFactor;
        float lateralTilt = MathHelper.clamp(state.horizontalSpeed * 26.0f, 0.0f, 11.0f) * airborneFactor * weightFactor;

        float wobbleDecay = 1.0f - state.settleProgress;
        float wobbleWave = (float) Math.sin((state.age * 0.8f) + (state.interpolatedRotation * 0.12f));
        float impactTilt = wobbleWave * 8.0f * state.impactAmplitude * wobbleDecay * weightFactor;
        float impactLift = Math.abs(wobbleWave) * 0.05f * state.impactAmplitude * wobbleDecay;

        float squash = 1.0f - (0.08f * state.impactAmplitude * wobbleDecay);
        float stretch = 1.0f + (0.08f * state.impactAmplitude * wobbleDecay);

        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(state.renderYaw + (state.interpolatedRotation * 0.15f)));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(state.renderPitch + forwardTilt + impactTilt));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(state.roll + lateralTilt - (impactTilt * 0.5f)));



        BlockState stateToRender = state.blockState;
//        System.out.println("Rendering block state: " + stateToRender + " (RenderType: " + stateToRender.getRenderType() + ")");


        if (stateToRender == null) {
            matrices.pop();
            return;
        }

        // Compute a vertical offset so that the model's bottom stays on the ground.
        double blockHeight = 1.0;
        double pitchRad = Math.toRadians(Math.abs(state.renderPitch));
        double verticalOffset = blockHeight * Math.sin(pitchRad);
        // Move upward by the offset.
        matrices.translate(0, verticalOffset + impactLift, 0);

        if (state.timerState > 0) {
            // Keep the compressed model visually grounded while scaling during impact.
            matrices.translate(0.0, (1.0f - squash) * 0.5f, 0.0);
            matrices.scale(stretch, squash, stretch);
        }


        BlockPos pos = state.renderPos;
        var world = entity.getWorld();
        var model = this.blockRenderManager.getModel(stateToRender);
//        System.out.println("Baked model " + model);
        var vertexConsumer = vertexConsumers.getBuffer(RenderLayers.getMovingBlockLayer(stateToRender));

        boolean cull = false;
        var random = world.getRandom();
        long seed = stateToRender.getRenderingSeed(pos);
        int overlay = OverlayTexture.DEFAULT_UV;

        BlockModelRenderer renderer = this.blockRenderManager.getModelRenderer();
        renderer.render(
                world,
                model,
                stateToRender,
                pos,
                matrices,
                vertexConsumer,
                cull,
                random,
                seed,
                overlay
        );

        float normalizedProgress = MathHelper.clamp(state.miningProgress / MINING_THRESHOLD, 0.0f, 1.0f);
        int stage = MathHelper.clamp((int) (normalizedProgress * 10.0f) - 1, -1, 9);
        if (stage >= 0) {
            Identifier crackTexture = Identifier.of("minecraft", "textures/block/destroy_stage_" + stage + ".png");
            VertexConsumer crackConsumer = vertexConsumers.getBuffer(RenderLayer.getBlockBreaking(crackTexture));
            VertexConsumer overlayConsumer = new OverlayVertexConsumer(crackConsumer, matrices.peek(), 1.0f);
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
        return Identifier.of("minecraft", "textures/block/stone.png");
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
