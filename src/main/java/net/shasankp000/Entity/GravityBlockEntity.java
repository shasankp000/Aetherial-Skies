package net.shasankp000.Entity;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.loot.context.LootWorldContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.Identifier;

import net.minecraft.util.math.BlockPos;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.shasankp000.Gravity.GravityData;
import net.shasankp000.Util.BlockStateRegistry;
import net.shasankp000.Util.HandMineableBlocks;
import net.shasankp000.Util.ToolStrength;


import java.util.List;


import static net.minecraft.util.math.MathHelper.lerp;


public class GravityBlockEntity extends Entity {
    private BlockState blockState;

    // Physics fields:
    private float rotationAngle;         // Current rotation angle (in degrees)
    private float previousRotationAngle; // Rotation angle from the previous tick (for interpolation)
    private float angularVelocity;       // Degrees per tick
    private float angularMomentum;       // For a more advanced simulation (not used in our simple model)

    public float pitchAngularVelocity = 0.0f;
    public float rollAngularVelocity = 0.0f;
    public float roll = 0.0f; // current roll angle


    // For a uniform block, center of mass is at the center; if you need an offset, store it here.
    private Vec3d centerOfMassOffset = new Vec3d(0.5, 0.5, 0.5); // default center for a 1x1x1 block

    private double weight = 0.04D; // default weight of the block

    // Example: add a landing timer field to your entity:
    private int landingTimer = 0;
    private int settleTicks = 0;

    private static final int REQUIRED_SLEEP_TICKS = 14;
    private static final double SLEEP_LINEAR_THRESHOLD = 0.0009D;
    private static final double WAKE_LINEAR_THRESHOLD = 0.01D;
    private static final float SLEEP_ANGULAR_THRESHOLD = 0.08f;
    private static final float WAKE_ANGULAR_THRESHOLD = 0.18f;

    // In GravityBlockEntity
    private float miningProgress = 0.0f;
    private int lastDamageTick = 0;
    private static final int RESET_THRESHOLD = 6;
    private static final float MINING_DECAY_PER_TICK = 0.02f;
    private static final float MINING_THRESHOLD = 1.0f;



    // Create a DataTracker entry for BlockState.
    private static final TrackedData<BlockState> BLOCK_STATE =
            DataTracker.registerData(GravityBlockEntity.class, TrackedDataHandlerRegistry.BLOCK_STATE);

    private static final TrackedData<Integer> TIMER_STATE =
            DataTracker.registerData(GravityBlockEntity.class, TrackedDataHandlerRegistry.INTEGER);

    private static final TrackedData<String> BLOCK_ID =
            DataTracker.registerData(GravityBlockEntity.class, TrackedDataHandlerRegistry.STRING);

    private static final TrackedData<Float> ROTATION =
            DataTracker.registerData(GravityBlockEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> PREVIOUS_ROTATION =
            DataTracker.registerData(GravityBlockEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private static final TrackedData<Float> ROLL =
            DataTracker.registerData(GravityBlockEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private static final TrackedData<Float> MINING_PROGRESS =
            DataTracker.registerData(GravityBlockEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private static final TrackedData<Float> VERTICAL_SPEED =
            DataTracker.registerData(GravityBlockEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private static final TrackedData<Float> HORIZONTAL_SPEED =
            DataTracker.registerData(GravityBlockEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private static final TrackedData<Float> IMPACT_AMPLITUDE =
            DataTracker.registerData(GravityBlockEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private static final TrackedData<Float> SETTLE_PROGRESS =
            DataTracker.registerData(GravityBlockEntity.class, TrackedDataHandlerRegistry.FLOAT);

    // Constructor for entity creation
    public GravityBlockEntity(EntityType<?> type, World world) {
        super(type, world);
        this.noClip = false; // other entities can't pass through this entity or vice versa.
    }

    @Override
    public Packet<ClientPlayPacketListener> createSpawnPacket(EntityTrackerEntry entityTrackerEntry) {
        return new EntitySpawnS2CPacket(this, entityTrackerEntry);
    }


    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(BLOCK_STATE, Blocks.AIR.getDefaultState());
        builder.add(BLOCK_ID, "minecraft:air"); // default value.
        builder.add(ROTATION, 0.0f);
        builder.add(PREVIOUS_ROTATION, 0.0f);
        builder.add(TIMER_STATE, 0);
        builder.add(ROLL, 0.0f);
        builder.add(MINING_PROGRESS, 0.0f);
        builder.add(VERTICAL_SPEED, 0.0f);
        builder.add(HORIZONTAL_SPEED, 0.0f);
        builder.add(IMPACT_AMPLITUDE, 0.0f);
        builder.add(SETTLE_PROGRESS, 0.0f);
    }

    @Override
    public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        if (data.equals(BLOCK_STATE)) {
            this.blockState = this.getDataTracker().get(BLOCK_STATE);
        }
    }

    // Setter for the block state
    public void setBlockState(BlockState state) {
        BlockState resolved = state == null ? Blocks.AIR.getDefaultState() : state;
        this.blockState = resolved;

        Identifier blockId = Registries.BLOCK.getId(resolved.getBlock());
        this.getDataTracker().set(BLOCK_ID, blockId.toString());
        this.getDataTracker().set(BLOCK_STATE, resolved);

    }

    public String getBlockIdString() {
        return this.getDataTracker().get(BLOCK_ID);
    }

    public float getCurrentRotation() {
        return this.getDataTracker().get(ROTATION);
    }

    public float getPreviousRotation() {
        return this.getDataTracker().get(PREVIOUS_ROTATION);
    }

    public int getLandingTimer() {
        return this.getDataTracker().get(TIMER_STATE);
    }

    // Getter for rendering purposes
    public BlockState getBlockState() {
        if (this.blockState != null) {
            return this.blockState;
        }
        return this.getDataTracker().get(BLOCK_STATE);
    }

    public float getRoll() {
        return this.getDataTracker().get(ROLL);
    }

    public float getMiningProgress() {
        return this.getDataTracker().get(MINING_PROGRESS);
    }

    public float getTrackedVerticalSpeed() {
        return this.getDataTracker().get(VERTICAL_SPEED);
    }

    public float getTrackedHorizontalSpeed() {
        return this.getDataTracker().get(HORIZONTAL_SPEED);
    }

    public float getTrackedImpactAmplitude() {
        return this.getDataTracker().get(IMPACT_AMPLITUDE);
    }

    public float getTrackedSettleProgress() {
        return this.getDataTracker().get(SETTLE_PROGRESS);
    }

    @Override
    public void tick() {
        super.tick();

        if (this.getWorld().isClient) {
            return; // only process physics on the server.
        }

        // Vanilla-like regression: progress decays while not being actively mined.
        if (this.age - lastDamageTick > RESET_THRESHOLD && miningProgress > 0.0f) {
            miningProgress = Math.max(0.0f, miningProgress - MINING_DECAY_PER_TICK);
            this.getDataTracker().set(MINING_PROGRESS, miningProgress);
        }

        // 1. Integrate linear motion from profile-driven gravity + drag.
        GravityData.PhysicsProfile profile = GravityData.getProfile(this.getBlockState().getBlock());
        float mass = Math.max(0.5f, profile.mass());
        Vec3d velocity = this.getVelocity();

        if (!this.hasNoGravity()) {
            velocity = velocity.add(0.0D, -profile.gravityAccel(), 0.0D);
        }
        double drag = MathHelper.clamp(1.0f - profile.airDrag(), 0.90f, 0.995f);
        velocity = new Vec3d(velocity.x * drag, velocity.y, velocity.z * drag);
        this.setVelocity(velocity);
        this.move(MovementType.SELF, this.getVelocity());

        // 2. Resolve collisions using profile-driven restitution + friction.
        Vec3d resolvedVelocity = this.resolveCollisionResponse(this.getVelocity(), profile, mass);
        this.setVelocity(resolvedVelocity);

        // 3. Integrate and damp angular state after collision impulses.
        this.updateAngularMotion(profile, mass);

        // 4. Put the entity to sleep when stable on the ground to avoid jitter.
        this.updateSleepState();

        // 5. Keep renderer-facing telemetry server-authoritative via DataTracker.
        this.updateRenderTelemetry();
    }

    private void updateRenderTelemetry() {
        Vec3d velocity = this.getVelocity();
        float verticalSpeed = (float) velocity.y;
        float horizontalSpeed = (float) velocity.horizontalLength();

        float settle = MathHelper.clamp(this.landingTimer / 10.0f, 0.0f, 1.0f);
        float impact = 0.0f;
        if (this.landingTimer > 0) {
            float motionImpact = MathHelper.clamp(
                    Math.abs(verticalSpeed) * 1.6f + horizontalSpeed * 1.1f,
                    0.0f,
                    1.0f
            );
            impact = Math.max(motionImpact, 1.0f - (settle * 0.8f));
        }

        this.getDataTracker().set(VERTICAL_SPEED, verticalSpeed);
        this.getDataTracker().set(HORIZONTAL_SPEED, horizontalSpeed);
        this.getDataTracker().set(SETTLE_PROGRESS, settle);
        this.getDataTracker().set(IMPACT_AMPLITUDE, impact);
    }

    private Vec3d resolveCollisionResponse(Vec3d velocity, GravityData.PhysicsProfile profile, float mass) {
        Vec3d result = velocity;
        boolean grounded = this.isOnGround() || (this.verticalCollision && result.y <= 0.0D);

        if (grounded) {
            landingTimer++;
            this.getDataTracker().set(TIMER_STATE, landingTimer);

            double normalInSpeed = Math.abs(Math.min(result.y, 0.0D));
            if (normalInSpeed > 0.03D) {
                double restitution = MathHelper.clamp(profile.restitution(), 0.0f, 0.8f);
                double bouncedY = normalInSpeed * restitution;
                if (bouncedY < 0.04D) {
                    bouncedY = 0.0D;
                }

                result = new Vec3d(result.x, bouncedY, result.z);

                float impactTorque = (float) MathHelper.clamp(normalInSpeed * 24.0D / mass, 0.0D, 6.0D);
                this.pitchAngularVelocity += impactTorque;
                this.rollAngularVelocity += impactTorque * 0.35f * (this.random.nextBoolean() ? 1.0f : -1.0f);
                this.angularVelocity *= 0.7f;
            } else {
                result = new Vec3d(result.x, 0.0D, result.z);
            }

            result = this.applyGroundFriction(result, profile, mass);

            BlockPos pos = this.getBlockPos();
            double settleAlpha = MathHelper.clamp(landingTimer / 18.0D, 0.0D, 0.35D);
            double centeredX = lerp(settleAlpha, this.getX(), pos.getX() + 0.5D);
            double centeredZ = lerp(settleAlpha, this.getZ(), pos.getZ() + 0.5D);
            this.setPosition(centeredX, this.getY(), centeredZ);
        } else {
            landingTimer = 0;
            settleTicks = 0;
            this.getDataTracker().set(TIMER_STATE, landingTimer);
        }

        if (this.horizontalCollision) {
            double wallDamping = 0.25D + (MathHelper.clamp(profile.restitution(), 0.0f, 0.6f) * 0.35D);
            result = new Vec3d(-result.x * wallDamping, result.y, -result.z * wallDamping);

            float wallTorque = (float) MathHelper.clamp(result.horizontalLength() * 6.0D / mass, 0.0D, 4.0D);
            this.angularVelocity += wallTorque;
        }

        return result;
    }

    private Vec3d applyGroundFriction(Vec3d velocity, GravityData.PhysicsProfile profile, float mass) {
        double retention = MathHelper.clamp(profile.groundFriction(), 0.40f, 0.90f);
        double staticCutoff = 0.018D * mass;

        double velX = velocity.x * retention;
        double velZ = velocity.z * retention;

        if (Math.abs(velX) < staticCutoff) {
            velX = 0.0D;
        }
        if (Math.abs(velZ) < staticCutoff) {
            velZ = 0.0D;
        }

        return new Vec3d(velX, velocity.y, velZ);
    }

    private void updateAngularMotion(GravityData.PhysicsProfile profile, float mass) {
        float deltaYaw = this.angularVelocity / mass;
        float deltaPitch = this.pitchAngularVelocity / mass;

        float newYaw = this.getYaw() + deltaYaw;
        float newPitch = MathHelper.clamp(this.getPitch() + deltaPitch, -90.0f, 90.0f);

        this.roll += this.rollAngularVelocity;
        this.setRotation(newYaw, newPitch);

        this.previousRotationAngle = this.rotationAngle;
        this.rotationAngle += this.angularVelocity;

        this.getDataTracker().set(PREVIOUS_ROTATION, this.previousRotationAngle);
        this.getDataTracker().set(ROTATION, this.rotationAngle);
        this.getDataTracker().set(ROLL, this.roll);

        float angularDamping = MathHelper.clamp(1.0f - profile.angularDrag(), 0.88f, 0.995f);
        this.angularVelocity *= angularDamping;
        this.pitchAngularVelocity *= angularDamping;
        this.rollAngularVelocity *= angularDamping;
    }

    private void updateSleepState() {
        Vec3d velocity = this.getVelocity();
        double linearMagnitude = velocity.lengthSquared();
        float angularMagnitude = Math.abs(this.angularVelocity) + Math.abs(this.pitchAngularVelocity) + Math.abs(this.rollAngularVelocity);
        boolean grounded = this.isOnGround() || landingTimer > 0;

        if (grounded && linearMagnitude < SLEEP_LINEAR_THRESHOLD && angularMagnitude < SLEEP_ANGULAR_THRESHOLD) {
            settleTicks++;
            if (settleTicks >= REQUIRED_SLEEP_TICKS) {
                this.setVelocity(0.0D, 0.0D, 0.0D);
                this.angularVelocity = 0.0f;
                this.pitchAngularVelocity = 0.0f;
                this.rollAngularVelocity = 0.0f;
            }
            return;
        }

        if (linearMagnitude > WAKE_LINEAR_THRESHOLD || angularMagnitude > WAKE_ANGULAR_THRESHOLD || !grounded) {
            settleTicks = 0;
        }
    }

    @Override
    public void pushAwayFrom(Entity other) {
        if (!this.isConnectedThroughVehicle(other)) {
            if (!other.noClip && !this.noClip) {
                // Avoid a "force field" when standing on top: only push if bodies truly overlap laterally.
                if (!this.getBoundingBox().intersects(other.getBoundingBox())) {
                    return;
                }
                if (other.getBoundingBox().minY >= this.getBoundingBox().maxY - 0.02D) {
                    return;
                }

                double deltaX = other.getX() - this.getX();
                double deltaZ = other.getZ() - this.getZ();
                double maxAbs = MathHelper.absMax(deltaX, deltaZ);
                if (maxAbs >= 0.01) {
                    double distance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
                    deltaX /= distance;
                    deltaZ /= distance;
                    GravityData.PhysicsProfile profile = GravityData.getProfile(this.getDataTracker().get(BLOCK_STATE).getBlock());
                    float mass = Math.max(0.5f, profile.mass());
                    float collisionStrictness = MathHelper.clamp(mass / 3.0f, 0.45f, 1.35f);

                    // Heavier blocks feel more "solid" and displace entities more decisively.
                    double pushForce = 0.035 + (0.045 * collisionStrictness);
                    double pushX = deltaX * pushForce;
                    double pushZ = deltaZ * pushForce;

                    // Make the gravity block feel solid by pushing others away from its center.
                    if (!other.hasPassengers() && other.isPushable()) {
                        double otherScale = other instanceof PlayerEntity ? (1.05 + (0.20 * collisionStrictness)) : (0.65 + (0.20 * collisionStrictness));
                        other.addVelocity(pushX * otherScale, 0.0, pushZ * otherScale);
                    }

                    if (!this.hasPassengers() && this.isPushable()) {
                        // Heavier blocks recoil less when colliding.
                        double recoilScale = MathHelper.clamp(0.22 - (0.10 * collisionStrictness), 0.06, 0.22);
                        this.addVelocity(-pushX * recoilScale, 0.0, -pushZ * recoilScale);
                    }
                }
            }
        }
    }


    @Override
    public void onPlayerCollision(PlayerEntity player) {
        // simply call pushAwayFrom(player)
        this.pushAwayFrom(player);
    }

    @Override
    public boolean isPushable() {
        return true;
    }

    @Override
    public boolean isCollidable() {
        return true;
    }

    @Override
    public boolean canHit() {
        return true;
    }

    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        boolean value = false;
        if (!world.isClient) {
            if (source.getAttacker() instanceof ServerPlayerEntity player) {
                if (!player.isCreative()) {
                    // Keep mining cadence vanilla-like: at most one progress step per tick.
                    if (lastDamageTick == this.age) {
                        return true;
                    }
                    lastDamageTick = this.age;


                    BlockPos pos = this.getBlockPos();
                    BlockState state = this.getBlockState();

                    if (state == null) {
                        // Fallback
                        state = BlockStateRegistry.getDefaultStateFor("air");
                    }

                    // keep playing the block breaking sound.

                    SoundEvent hitSound = state.getSoundGroup().getHitSound();
                    SoundEvent breakSound = state.getSoundGroup().getBreakSound();
                    world.playSound(null, pos, hitSound, SoundCategory.BLOCKS, 1.0F, 1.0F);


                    float hardness = state.getHardness(world, pos);
                    if (hardness < 0.0f) {
                        return false;
                    }
                    hardness = Math.max(hardness, 0.25f);

                    ItemStack tool = player.getMainHandStack();
                    boolean canHarvest = !state.isToolRequired() || player.canHarvest(state);
                    float breakingSpeed = player.getBlockBreakingSpeed(state);
                    if (breakingSpeed <= 0.0f) {
                        return false;
                    }

                    // Vanilla-like: speed / hardness / (30 when harvestable, else 100).
                    float vanillaDelta = breakingSpeed / hardness / (canHarvest ? 30.0f : 100.0f);

                    float progressIncrement = vanillaDelta;

                    // Keep tool-material differences visible (wood < stone < iron < diamond/netherite).
                    if (!tool.isEmpty()) {
                        String toolPath = Registries.ITEM.getId(tool.getItem()).getPath();
                        progressIncrement *= ToolStrength.getToolMultiplier(toolPath);
                    } else {
                        // Hand mining should still be valid and show crack progression.
                        if (HandMineableBlocks.isHandMineable(this.getBlockIdString())) {
                            progressIncrement *= 1.15f;
                        } else {
                            // Non-hand-mineable blocks remain slower by hand, but still progress.
                            progressIncrement *= 0.55f;
                        }
                    }

                    // Prevent pathological spikes while preserving material differences.
                    progressIncrement = MathHelper.clamp(progressIncrement, 0.0f, 0.25f);

                    // Ensure tiny-but-visible progress for hand mining so cracks appear as expected.
                    if (tool.isEmpty()) {
                        progressIncrement = Math.max(progressIncrement, 0.0012f);
                    }


                    // Increase the mining progress.
                    this.miningProgress += progressIncrement;
                    this.getDataTracker().set(MINING_PROGRESS, this.miningProgress);


                    // If the mining progress exceeds the threshold, finalize the break.
                    if (this.miningProgress >= MINING_THRESHOLD) {
                        // Play the block break sound (using the block's sound group)
                        world.playSound(null, pos, breakSound, SoundCategory.BLOCKS, 1.0F, 1.0F);


                        // Drop the items.
                        LootWorldContext.Builder builder = (new LootWorldContext.Builder(world))
                                .add(LootContextParameters.ORIGIN, Vec3d.ofCenter(pos))
                                .add(LootContextParameters.TOOL, tool)
                                .addOptional(LootContextParameters.BLOCK_ENTITY, null);
                        List<ItemStack> drops = state.getDroppedStacks(builder);
                        if (!drops.isEmpty()) {
                            for (ItemStack drop : drops) {
                                ItemScatterer.spawn(world, pos.getX(), pos.getY(), pos.getZ(), drop);
                            }
                        }

                        this.discard();
                        miningProgress = 0.0f; // reset mining progress.
                        this.getDataTracker().set(MINING_PROGRESS, this.miningProgress);

                        value = true;
                    }
                }
                else {
                    // player is in creative mode, we just discard the entity.
                    BlockState state = this.getBlockState();
                    BlockPos pos = this.getBlockPos();
                    SoundEvent breakSound = state.getSoundGroup().getBreakSound();
                    world.playSound(null, pos, breakSound, SoundCategory.BLOCKS, 1.0F, 1.0F);
                    this.discard();
                }

            }
        }
        return value;
    }

    private static String getPathOnly(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        String normalized = id.toLowerCase();
        if (normalized.contains(":")) {
            normalized = normalized.substring(normalized.indexOf(':') + 1);
        }
        return normalized;
    }



    // Save custom data (block state) to NBT
    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putFloat("RotationAngle", rotationAngle);
        nbt.putFloat("PreviousRotationAngle", previousRotationAngle);
        nbt.putFloat("AngularVelocity", angularVelocity);
        nbt.putFloat("PitchAngularVelocity", pitchAngularVelocity);
        nbt.putFloat("RollAngularVelocity", rollAngularVelocity);
        nbt.putFloat("Roll", this.getRoll());
        nbt.putInt("LandingTimer", this.getLandingTimer());
        nbt.putFloat("MiningProgress", this.getMiningProgress());
        nbt.putFloat("VerticalSpeed", this.getTrackedVerticalSpeed());
        nbt.putFloat("HorizontalSpeed", this.getTrackedHorizontalSpeed());
        nbt.putFloat("ImpactAmplitude", this.getTrackedImpactAmplitude());
        nbt.putFloat("SettleProgress", this.getTrackedSettleProgress());
        nbt.putDouble("Weight", this.weight);
        nbt.putInt("SettleTicks", this.settleTicks);
        nbt.putString("BlockId", this.getBlockIdString());
        if (this.blockState != null) {
            nbt.put("BlockState", NbtHelper.fromBlockState(this.blockState));
        }
    }

    // Load custom data (block state) from NBT
    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {

        rotationAngle = nbt.getFloat("RotationAngle");
        previousRotationAngle = nbt.contains("PreviousRotationAngle") ? nbt.getFloat("PreviousRotationAngle") : rotationAngle;
        angularVelocity = nbt.getFloat("AngularVelocity");
        pitchAngularVelocity = nbt.contains("PitchAngularVelocity") ? nbt.getFloat("PitchAngularVelocity") : 0.0f;
        rollAngularVelocity = nbt.contains("RollAngularVelocity") ? nbt.getFloat("RollAngularVelocity") : 0.0f;
        this.roll = nbt.contains("Roll") ? nbt.getFloat("Roll") : 0.0f;
        this.landingTimer = nbt.contains("LandingTimer") ? nbt.getInt("LandingTimer") : 0;
        this.settleTicks = nbt.contains("SettleTicks") ? nbt.getInt("SettleTicks") : 0;
        this.miningProgress = nbt.contains("MiningProgress") ? nbt.getFloat("MiningProgress") : 0.0f;
        this.weight = nbt.contains("Weight") ? nbt.getDouble("Weight") : this.weight;

        RegistryWrapper.WrapperLookup registryLookup = this.getWorld().getRegistryManager();
        RegistryEntryLookup<Block> blockLookup = registryLookup.getOrThrow(RegistryKeys.BLOCK);
        BlockState restoredState = null;
        if (nbt.contains("BlockState")) {
            restoredState = NbtHelper.toBlockState(blockLookup, nbt.getCompound("BlockState"));
        } else if (nbt.contains("BlockId")) {
            restoredState = BlockStateRegistry.getDefaultStateFor(nbt.getString("BlockId"));
        }

        this.setBlockState(restoredState == null ? Blocks.AIR.getDefaultState() : restoredState);
        this.getDataTracker().set(ROLL, this.roll);
        this.getDataTracker().set(TIMER_STATE, this.landingTimer);
        this.getDataTracker().set(MINING_PROGRESS, this.miningProgress);
        this.getDataTracker().set(ROTATION, this.rotationAngle);
        this.getDataTracker().set(PREVIOUS_ROTATION, this.previousRotationAngle);
        this.getDataTracker().set(VERTICAL_SPEED, nbt.contains("VerticalSpeed") ? nbt.getFloat("VerticalSpeed") : 0.0f);
        this.getDataTracker().set(HORIZONTAL_SPEED, nbt.contains("HorizontalSpeed") ? nbt.getFloat("HorizontalSpeed") : 0.0f);
        this.getDataTracker().set(IMPACT_AMPLITUDE, nbt.contains("ImpactAmplitude") ? nbt.getFloat("ImpactAmplitude") : 0.0f);
        this.getDataTracker().set(SETTLE_PROGRESS, nbt.contains("SettleProgress") ? nbt.getFloat("SettleProgress") : 0.0f);
    }



    // getters and setters

    public Vec3d getCenterOfMassOffset() {
        return centerOfMassOffset;
    }

    public void setCenterOfMassOffset(Vec3d centerOfMassOffset) {
        this.centerOfMassOffset = centerOfMassOffset;
    }

    public float getRotationAngle() {
        return rotationAngle;
    }

    public void setRotationAngle(float rotationAngle) {
        this.rotationAngle = rotationAngle;
    }

    public float getPreviousRotationAngle() {
        return previousRotationAngle;
    }

    public void setPreviousRotationAngle(float previousRotationAngle) {
        this.previousRotationAngle = previousRotationAngle;
    }

    public float getAngularVelocity() {
        return angularVelocity;
    }

    public void setAngularVelocity(float angularVelocity) {
        this.angularVelocity = angularVelocity;
    }

    public float getAngularMomentum() {
        return angularMomentum;
    }

    public void setAngularMomentum(float angularMomentum) {
        this.angularMomentum = angularMomentum;
    }

    public double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        this.weight = weight;
    }
}