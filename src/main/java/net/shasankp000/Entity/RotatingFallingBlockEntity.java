package net.shasankp000.Entity;

import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class RotatingFallingBlockEntity extends FallingBlockEntity {
    // A simple rotationAngle angle in degrees (for example, around the Z axis)
    private float rotationAngle;
    // Angular velocity in degrees per tick.
    private float angularVelocity;
    private float previousRotationAngle; // Stores last tick's rotation

    // Store the block state we want to render
    private BlockState fallingState;
    private BlockState blockState; // Assuming this field exists


    public RotatingFallingBlockEntity(EntityType<? extends RotatingFallingBlockEntity> type, World world) {
        super(type, world);
        this.rotationAngle = 0f;
        this.angularVelocity = 0f;
    }

    public void setBlockState(BlockState state) {
        this.blockState = state;
    }

    // Public setter for our falling block state.
    public void setFallingState(BlockState state) {
        this.fallingState = state;
    }

    // Public getter (used by the renderer, for example).
    public BlockState getFallingState() {
        return fallingState;
    }


    public float getAngularVelocity() {
        return angularVelocity;
    }

    public void setAngularVelocity(float angularVelocity) {
        this.angularVelocity = angularVelocity;
    }

    /**
     * Returns the current rotationAngle angle (in degrees).
     * The tickDelta parameter can be used to interpolate if desired.
     */
    public float getRotationAngle(float tickDelta) {

        // Here we perform interpolation to calculate a discrete rotation angle frame between ticks instead of just directly hopping from one tick to the next.

        return previousRotationAngle + (rotationAngle - previousRotationAngle) * tickDelta;
    }

    /**
     * Custom helper method to spawn a RotatingFallingBlockEntity.
     * This method constructs the entity, sets its falling block state,
     * positions it, and returns the entity.
     */
    public static RotatingFallingBlockEntity spawnFromBlock(ServerWorld world, BlockPos pos, BlockState state, EntityType<? extends RotatingFallingBlockEntity> type) {
        // Create our custom entity using our two-argument constructor.
        RotatingFallingBlockEntity entity = new RotatingFallingBlockEntity(type, world);
        // Set our custom falling state field.
        entity.setFallingState(state);
        entity.setBlockState(state); // Set the intended block state
        System.out.println("Spawned with blockState: " + entity.blockState);
        // Position the entity in the center of the block.
        entity.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        return entity;
    }

    @Override
    public void tick() {
        // Call the default falling block logic first.
        super.tick();

        // Update rotationAngle based on angular velocity.
        this.rotationAngle += this.angularVelocity;

        // Apply some damping (simulate friction/air resistance on rotationAngle).
        this.angularVelocity *= 0.98f;

        // Optionally, apply torque if colliding with the ground.
        if (this.isOnGround()) {
            // For example, add a small random angular impulse when hitting the ground.
            this.angularVelocity += (this.random.nextFloat() - 0.5f) * 2f;
        }
    }



    @Override
    public Packet<ClientPlayPacketListener> createSpawnPacket() {
        return super.createSpawnPacket();
    }


    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putFloat("RotationAngle", rotationAngle);
        nbt.putFloat("AngularVelocity", angularVelocity);
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        rotationAngle = nbt.getFloat("RotationAngle");
        angularVelocity = nbt.getFloat("AngularVelocity");
    }


}