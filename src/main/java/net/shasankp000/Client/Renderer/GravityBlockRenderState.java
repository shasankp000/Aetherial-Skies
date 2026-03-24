package net.shasankp000.Client.Renderer;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.shasankp000.Entity.GravityBlockEntity;

public class GravityBlockRenderState {

    public GravityBlockEntity entity;
    public float tickDelta;
    public BlockState blockState; // Added field to store the entity's BlockState
    public String blockIdString; // New field for the block identifier string
    public float currentRotation;
    public float previousRotation;
    public float interpolatedRotation; // New field for interpolation.
    public int timerState;
    public float roll;
    public float miningProgress;
    public float verticalSpeed;
    public float horizontalSpeed;
    public float blockWeight;
    public float age;
    public float impactAmplitude;
    public float settleProgress;
    public float renderYaw;
    public float renderPitch;
    public BlockPos renderPos;

    public GravityBlockRenderState() {

    }
}
