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
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.Identifier;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.shasankp000.Gravity.GravityData;
import net.shasankp000.Util.BlockStateRegistry;
import net.shasankp000.Util.HandMineableBlocks;
import net.shasankp000.Util.ToolStrength;


import java.util.List;
import java.util.UUID;


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

    // Separate settle counter for the water dead-band path.
    private int waterSettleTicks = 0;

    private static final int REQUIRED_SLEEP_TICKS = 14;
    private static final double SLEEP_LINEAR_THRESHOLD = 0.0009D;
    private static final double WAKE_LINEAR_THRESHOLD = 0.01D;
    private static final float SLEEP_ANGULAR_THRESHOLD = 0.08f;
    private static final float WAKE_ANGULAR_THRESHOLD = 0.18f;

    // Water dead-band: if |vel.y| is below this while floating, snap it to zero.
    private static final double WATER_SLEEP_VY_THRESHOLD = 0.0008D;
    // Require this many consecutive quiet ticks before snapping, to avoid locking during wave hits.
    private static final int WATER_REQUIRED_SLEEP_TICKS = 10;

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

    private static final TrackedData<Float> WAVE_METRIC =
            DataTracker.registerData(GravityBlockEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private static final TrackedData<Float> PLAYER_LOAD =
            DataTracker.registerData(GravityBlockEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private static final int PLAYER_LOAD_SAMPLE_INTERVAL = 4;
    private static final int TELEMETRY_UPDATE_INTERVAL = 2;

    private float smoothedPlayerLoad = 0.0f;
    private float lastWaveMetric = 0.0f;
    private float cachedPlayerLoadTarget = 0.0f;
    private int nextPlayerLoadSampleAge = 0;
    private int nextStackShearImpulseAge = 0;
    private boolean shipControlled = false;
    private UUID linkedShipUuid = null;

    // Constructor for entity creation
    public GravityBlockEntity(EntityType<?> type, World world) {
        super(type, world);
        this.noClip = false; // other entities can't pass through this entity or vice versa.
    }

    @Override
    public Packet<ClientPlayPacketListener> createSpawnPacket() {
        return new EntitySpawnS2CPacket(this);
    }


    @Override
    protected void initDataTracker() {
        this.dataTracker.startTracking(BLOCK_STATE, Blocks.AIR.getDefaultState());
        this.dataTracker.startTracking(BLOCK_ID, "minecraft:air"); // default value.
        this.dataTracker.startTracking(ROTATION, 0.0f);
        this.dataTracker.startTracking(PREVIOUS_ROTATION, 0.0f);
        this.dataTracker.startTracking(TIMER_STATE, 0);
        this.dataTracker.startTracking(ROLL, 0.0f);
        this.dataTracker.startTracking(MINING_PROGRESS, 0.0f);
        this.dataTracker.startTracking(VERTICAL_SPEED, 0.0f);
        this.dataTracker.startTracking(HORIZONTAL_SPEED, 0.0f);
        this.dataTracker.startTracking(IMPACT_AMPLITUDE, 0.0f);
        this.dataTracker.startTracking(SETTLE_PROGRESS, 0.0f);
        this.dataTracker.startTracking(WAVE_METRIC, 0.0f);
        this.dataTracker.startTracking(PLAYER_LOAD, 0.0f);
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

    public float getTrackedWaveMetric() {
        return this.getDataTracker().get(WAVE_METRIC);
    }

    public float getTrackedPlayerLoad() {
        return this.getDataTracker().get(PLAYER_LOAD);
    }

    @Override
    public void tick() {
        super.tick();

        if (this.getWorld().isClient) {
            return; // only process physics on the server.
        }

        if (this.shipControlled) {
            this.setVelocity(Vec3d.ZERO);
            this.updateRenderTelemetry();
            return;
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
            boolean inWater = this.isTouchingWater();
            boolean inLava = this.isInLava();
            float waterDepth = (float) this.getFluidHeight(FluidTags.WATER);
            float lavaDepth = (float) this.getFluidHeight(FluidTags.LAVA);
            float fluidDepth = MathHelper.clamp(Math.max(waterDepth, lavaDepth), 0.0f, 1.0f);

            if (inWater || inLava) {
                // Relative-density model (extensible to multi-block ships via GravityData.composeHydrodynamics* APIs).
                float fluidDensityScale = inLava ? 1.25f : 1.0f;
                float effectiveRelativeDensity = profile.relativeDensity() / fluidDensityScale;

                GravityData.WaveProfile waveProfile = GravityData.getWaveProfile(this.getWorld(), this.getBlockPos());
                float playerLoadTarget = this.getSampledPlayerLoadTarget();
                // Smooth restoration when players step off instead of snapping instantly.
                this.smoothedPlayerLoad = MathHelper.lerp(0.12f, this.smoothedPlayerLoad, playerLoadTarget);
                if (this.smoothedPlayerLoad < 0.001f) {
                    this.smoothedPlayerLoad = 0.0f;
                }
                this.lastWaveMetric = waveProfile.waveMetric();

                // Submersion-dependent buoyancy. < 0 => upward acceleration (float), > 0 => sinking.
                float displacementTerm = fluidDepth * profile.displacedVolume();
                // Option C hybrid load model: buoyancy is reduced by a smooth count+mass-based player load.
                float netSpecificGravity = effectiveRelativeDensity - displacementTerm - (profile.buoyancyAssist() * fluidDepth) + this.smoothedPlayerLoad;
                float netGravity = profile.gravityAccel() * netSpecificGravity;

                velocity = velocity.add(0.0D, -netGravity, 0.0D);
                velocity = this.applyFluidWaveForces(velocity, waveProfile, fluidDepth, effectiveRelativeDensity, inLava);
                velocity = this.applyStackedFluidInteractions(velocity, fluidDepth, mass);

                // Fluids damp linear motion much more than air.
                double horizontalFluidDrag = MathHelper.clamp(0.86f - (fluidDepth * 0.10f), 0.62f, 0.90f);
                double verticalFluidDrag = MathHelper.clamp(0.88f - (fluidDepth * 0.14f), 0.58f, 0.92f);
                velocity = new Vec3d(velocity.x * horizontalFluidDrag, velocity.y * verticalFluidDrag, velocity.z * horizontalFluidDrag);

                // Loaded floating blocks should remain standable: add extra lateral stabilization under load.
                if (this.smoothedPlayerLoad > 0.0f) {
                    double loadStability = MathHelper.clamp(1.0f - (this.smoothedPlayerLoad * 0.60f), 0.58f, 1.0f);
                    velocity = new Vec3d(velocity.x * loadStability, velocity.y, velocity.z * loadStability);
                }

                // Avoid runaway upward acceleration while still allowing float-up for very light blocks.
                if (velocity.y > 0.12D) {
                    velocity = new Vec3d(velocity.x, 0.12D, velocity.z);
                }
            } else {
                this.smoothedPlayerLoad = MathHelper.lerp(0.10f, this.smoothedPlayerLoad, 0.0f);
                this.lastWaveMetric = 0.0f;
                velocity = velocity.add(0.0D, -profile.gravityAccel(), 0.0D);

                // Drive angular velocity from horizontal motion during freefall.
                double horizontalSpeed = velocity.horizontalLength();
                if (!this.isOnGround() && landingTimer == 0) {
                    float tumbleFactor = MathHelper.clamp(
                            (float) (horizontalSpeed * 2.5f) / mass,
                            0.0f,
                            2.5f
                    );
                    angularVelocity += tumbleFactor;
                    pitchAngularVelocity += tumbleFactor * 0.4f * (this.random.nextBoolean() ? 1f : -1f);
                }
            }
        }

        double drag = MathHelper.clamp(1.0f - profile.airDrag(), 0.90f, 0.995f);
        if (!this.isTouchingWater() && !this.isInLava()) {
            velocity = new Vec3d(velocity.x * drag, velocity.y, velocity.z * drag);
        }
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
        if ((this.age % TELEMETRY_UPDATE_INTERVAL) != 0) {
            return;
        }

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
        this.getDataTracker().set(WAVE_METRIC, this.lastWaveMetric);
        this.getDataTracker().set(PLAYER_LOAD, this.smoothedPlayerLoad);
    }

    private float getSampledPlayerLoadTarget() {
        if (this.age >= this.nextPlayerLoadSampleAge) {
            this.cachedPlayerLoadTarget = this.computePlayerLoadTarget();
            this.nextPlayerLoadSampleAge = this.age + PLAYER_LOAD_SAMPLE_INTERVAL + (this.getId() & 1);
        }
        return this.cachedPlayerLoadTarget;
    }

    private Vec3d applyFluidWaveForces(Vec3d velocity, GravityData.WaveProfile waveProfile, float fluidDepth, float effectiveRelativeDensity, boolean inLava) {
        long worldTime = this.getWorld().getTime();
        double phase = (worldTime * waveProfile.frequency()) + (this.getX() * 0.23D) + (this.getZ() * 0.19D);
        double wave = Math.sin(phase) * waveProfile.verticalAmplitude();
        double secondaryWave = Math.cos((phase * 0.63D) + 1.2D) * waveProfile.turbulence();

        float waveResponse = MathHelper.clamp(1.18f - (effectiveRelativeDensity * 0.45f) - (this.smoothedPlayerLoad * 1.1f), 0.15f, 1.2f);
        if (inLava) {
            waveResponse *= 0.65f;
        }

        // Prevent excessive lateral slip while standing on floating blocks.
        float driftResponse = MathHelper.clamp(waveResponse * (1.0f - (this.smoothedPlayerLoad * 1.35f)), 0.08f, 1.0f);

        double waveVerticalAccel = (wave + secondaryWave) * waveProfile.waveMetric() * fluidDepth * waveResponse;
        double driftX = Math.cos(phase * 0.41D) * waveProfile.horizontalDrift() * waveProfile.waveMetric() * fluidDepth * driftResponse;
        double driftZ = Math.sin((phase * 0.37D) + 0.8D) * waveProfile.horizontalDrift() * waveProfile.waveMetric() * fluidDepth * driftResponse;

        return velocity.add(driftX, waveVerticalAccel, driftZ);
    }

    private float computePlayerLoadTarget() {
        Box topBand = this.getBoundingBox()
                .expand(-0.06D, 0.0D, -0.06D)
                .stretch(0.0D, 0.45D, 0.0D)
                .offset(0.0D, 0.02D, 0.0D);

        List<PlayerEntity> players = this.getWorld().getEntitiesByClass(
                PlayerEntity.class,
                topBand,
                player -> !player.isSpectator() && player.getBoundingBox().minY >= this.getBoundingBox().maxY - 0.03D
        );

        float massProxyTotal = 0.0f;
        for (PlayerEntity player : players) {
            massProxyTotal += this.estimatePlayerMassProxy(player);
        }

        float countTerm = Math.min(0.32f, players.size() * 0.10f);
        float massTerm = Math.min(0.35f, massProxyTotal * 0.12f);

        List<GravityBlockEntity> stackedBlocks = this.getStackedBlocksAbove();
        float stackedMassProxy = 0.0f;
        for (GravityBlockEntity stacked : stackedBlocks) {
            float stackedMass = Math.max(0.5f, GravityData.getProfile(stacked.getBlockState().getBlock()).mass());
            stackedMassProxy += MathHelper.clamp(stackedMass * 0.58f, 0.30f, 1.60f);
        }

        float stackedCountTerm = Math.min(0.40f, stackedBlocks.size() * 0.16f);
        float stackedMassTerm = Math.min(0.72f, stackedMassProxy * 0.26f);
        float playerLoad = (countTerm * 0.55f) + (massTerm * 0.45f);
        float stackedLoad = (stackedCountTerm * 0.40f) + (stackedMassTerm * 0.60f);
        return MathHelper.clamp(playerLoad + stackedLoad, 0.0f, 1.25f);
    }

    private List<GravityBlockEntity> getStackedBlocksAbove() {
        Box thisBox = this.getBoundingBox();
        Box stackedBand = thisBox
                .expand(-0.04D, 0.0D, -0.04D)
                .stretch(0.0D, 1.10D, 0.0D)
                .offset(0.0D, 0.02D, 0.0D);

        return this.getWorld().getEntitiesByClass(
                GravityBlockEntity.class,
                stackedBand,
                block -> this.isStackedOnTop(block, thisBox)
        );
    }

    private boolean isStackedOnTop(GravityBlockEntity block, Box thisBox) {
        if (block == this) return false;
        Box otherBox = block.getBoundingBox();
        double verticalOffset = otherBox.minY - thisBox.maxY;
        if (verticalOffset < -0.32D || verticalOffset > 0.72D) return false;
        double overlapEpsilon = 0.04D;
        boolean overlapsX = otherBox.maxX >= thisBox.minX + overlapEpsilon && otherBox.minX <= thisBox.maxX - overlapEpsilon;
        boolean overlapsZ = otherBox.maxZ >= thisBox.minZ + overlapEpsilon && otherBox.minZ <= thisBox.maxZ - overlapEpsilon;
        if (!overlapsX || !overlapsZ) return false;
        return block.getX() >= thisBox.minX - 0.10D && block.getX() <= thisBox.maxX + 0.10D
                && block.getZ() >= thisBox.minZ - 0.10D && block.getZ() <= thisBox.maxZ + 0.10D;
    }

    private Vec3d applyStackedFluidInteractions(Vec3d velocity, float fluidDepth, float selfMass) {
        if (fluidDepth <= 0.0f || (this.age % 2) != 0) return velocity;
        List<GravityBlockEntity> stackedBlocks = this.getStackedBlocksAbove();
        if (stackedBlocks.isEmpty()) return velocity;

        float totalStackMass = 0.0f;
        for (GravityBlockEntity stacked : stackedBlocks) {
            totalStackMass += Math.max(0.5f, GravityData.getProfile(stacked.getBlockState().getBlock()).mass());
        }

        double stackedDownforce = MathHelper.clamp(totalStackMass * 0.022f * fluidDepth, 0.0f, 0.22f);
        Vec3d adjusted = velocity.add(0.0D, -stackedDownforce, 0.0D);

        for (GravityBlockEntity stacked : stackedBlocks) {
            float stackedMass = Math.max(0.5f, GravityData.getProfile(stacked.getBlockState().getBlock()).mass());
            if (stackedMass <= (selfMass * 1.05f)) continue;
            double dx = stacked.getX() - this.getX();
            double dz = stacked.getZ() - this.getZ();
            double d = Math.sqrt((dx * dx) + (dz * dz));
            if (d < 1.0E-4D) {
                double phase = (this.age * 0.31D) + (this.getId() * 0.17D);
                dx = Math.cos(phase); dz = Math.sin(phase); d = 1.0D;
            }
            dx /= d; dz /= d;
            double shear = MathHelper.clamp((stackedMass - selfMass) * 0.010f * fluidDepth, 0.0035f, 0.028f);
            stacked.addVelocity(dx * shear, 0.0D, dz * shear);
            this.addVelocity(-dx * shear * 0.25D, 0.0D, -dz * shear * 0.25D);
        }
        return adjusted;
    }

    private float estimatePlayerMassProxy(PlayerEntity player) {
        float proxy = 1.0f;
        for (ItemStack armorStack : player.getArmorItems()) {
            if (!armorStack.isEmpty()) proxy += 0.07f;
        }
        if (!player.getMainHandStack().isEmpty()) proxy += 0.04f;
        if (player.isSneaking()) proxy += 0.05f;
        return proxy;
    }

    public boolean isTopSupportedPlayer(Entity candidate) {
        if (!(candidate instanceof PlayerEntity other)) return false;
        Box thisBox = this.getBoundingBox();
        Box otherBox = other.getBoundingBox();
        if (!thisBox.intersects(otherBox)) return false;
        double footOffsetFromTop = otherBox.minY - thisBox.maxY;
        if (footOffsetFromTop < -0.30D || footOffsetFromTop > 0.30D) return false;
        boolean overTopPlane = other.getX() >= thisBox.minX && other.getX() <= thisBox.maxX
                && other.getZ() >= thisBox.minZ && other.getZ() <= thisBox.maxZ;
        if (!overTopPlane) return false;
        boolean aboveBlockMidline = otherBox.maxY > thisBox.maxY + 0.10D;
        boolean notStronglyAscending = other.getVelocity().y <= 0.16D;
        return aboveBlockMidline && notStronglyAscending;
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
                if (bouncedY < 0.04D) bouncedY = 0.0D;

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
        double retention = MathHelper.clamp(1.0f - (profile.groundFriction() * 0.55f), 0.35f, 0.72f);
        double staticCutoff = 0.018D * mass;
        GravityBlockEntity support = this.findSupportingGravityBlockBelow();
        if (support != null) {
            boolean floatingSupport = support.isTouchingWater() || support.isInLava() || !support.isOnGround();
            if (floatingSupport) {
                retention = MathHelper.clamp(retention + 0.18D, 0.55D, 0.92D);
                staticCutoff *= 0.35D;
            }
        }
        double velX = velocity.x * retention;
        double velZ = velocity.z * retention;
        if (Math.abs(velX) < staticCutoff) velX = 0.0D;
        if (Math.abs(velZ) < staticCutoff) velZ = 0.0D;
        return new Vec3d(velX, velocity.y, velZ);
    }

    private GravityBlockEntity findSupportingGravityBlockBelow() {
        Box thisBox = this.getBoundingBox();
        Box probe = thisBox.expand(-0.04D, 0.0D, -0.04D).stretch(0.0D, -0.22D, 0.0D).offset(0.0D, -0.02D, 0.0D);
        List<GravityBlockEntity> supports = this.getWorld().getEntitiesByClass(
                GravityBlockEntity.class, probe,
                block -> block != this && block.getBoundingBox().maxY <= thisBox.minY + 0.08D
        );
        return supports.isEmpty() ? null : supports.get(0);
    }

    private boolean applyFloatingStackShear(GravityBlockEntity other, Box thisBox, Box otherBox) {
        boolean otherAboveThis = otherBox.minY >= thisBox.maxY - 0.06D;
        boolean thisAboveOther = thisBox.minY >= otherBox.maxY - 0.06D;
        if (!otherAboveThis && !thisAboveOther) return false;

        GravityBlockEntity lower = otherAboveThis ? this : other;
        GravityBlockEntity upper = otherAboveThis ? other : this;
        boolean floatingStack = lower.isTouchingWater() || lower.isInLava() || !lower.isOnGround();
        if (!floatingStack) return true;

        if (this.age < this.nextStackShearImpulseAge && other.age < other.nextStackShearImpulseAge) return true;

        float lowerMass = Math.max(0.5f, GravityData.getProfile(lower.getBlockState().getBlock()).mass());
        float upperMass = Math.max(0.5f, GravityData.getProfile(upper.getBlockState().getBlock()).mass());
        float massExcess = upperMass - (lowerMass * 0.95f);
        if (massExcess <= 0.0f) return true;

        double dx = upper.getX() - lower.getX();
        double dz = upper.getZ() - lower.getZ();
        double distance = Math.sqrt((dx * dx) + (dz * dz));
        if (distance < 1.0E-4D) {
            double phase = (this.getWorld().getTime() * 0.41D) + (upper.getId() * 0.13D);
            dx = Math.cos(phase); dz = Math.sin(phase); distance = 1.0D;
        }
        dx /= distance; dz /= distance;

        float waveMetric = Math.max(lower.lastWaveMetric, upper.lastWaveMetric);
        double shear = MathHelper.clamp((massExcess * 0.013f) + (waveMetric * 0.004f), 0.0025f, 0.032f);
        Vec3d relativeVelocity = upper.getVelocity().subtract(lower.getVelocity());
        double relativeHorizontalSpeed = Math.sqrt((relativeVelocity.x * relativeVelocity.x) + (relativeVelocity.z * relativeVelocity.z));
        shear *= MathHelper.clamp(1.0D - (relativeHorizontalSpeed * 10.0D), 0.35D, 1.0D);
        upper.addVelocity(dx * shear, 0.0D, dz * shear);
        lower.addVelocity(-dx * shear * 0.22D, 0.0D, -dz * shear * 0.22D);
        upper.nextStackShearImpulseAge = upper.age + 2;
        lower.nextStackShearImpulseAge = lower.age + 2;

        double centerDistance = Math.sqrt(((upper.getX() - lower.getX()) * (upper.getX() - lower.getX())) + ((upper.getZ() - lower.getZ()) * (upper.getZ() - lower.getZ())));
        double centerBias = MathHelper.clamp(1.0D - centerDistance, 0.0D, 1.0D);
        double compressionDownforce = MathHelper.clamp((massExcess * 0.012f) + (centerBias * 0.015f), 0.0f, 0.065f);
        lower.addVelocity(0.0D, -compressionDownforce, 0.0D);
        upper.addVelocity(0.0D, -compressionDownforce * 0.35D, 0.0D);

        double relativeVerticalSpeed = upper.getVelocity().y - lower.getVelocity().y;
        if (Math.abs(relativeVerticalSpeed) > 0.01D) {
            upper.addVelocity(0.0D, -relativeVerticalSpeed * 0.24D, 0.0D);
            lower.addVelocity(0.0D, relativeVerticalSpeed * 0.12D, 0.0D);
        }
        return true;
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
        if (this.isTouchingWater() || this.isInLava()) {
            angularDamping *= this.isInLava() ? 0.72f : 0.82f;
        }
        this.angularVelocity *= angularDamping;
        this.pitchAngularVelocity *= angularDamping;
        this.rollAngularVelocity *= angularDamping;
    }

    private void updateSleepState() {
        Vec3d velocity = this.getVelocity();
        double linearMagnitude = velocity.lengthSquared();
        float angularMagnitude = Math.abs(this.angularVelocity) + Math.abs(this.pitchAngularVelocity) + Math.abs(this.rollAngularVelocity);
        boolean grounded = this.isOnGround() || landingTimer > 0;
        boolean inFluid = this.isTouchingWater() || this.isInLava();

        // --- Water dead-band: snap residual vertical creep to zero when floating is settled ---
        if (inFluid && !grounded) {
            if (Math.abs(velocity.y) < WATER_SLEEP_VY_THRESHOLD && velocity.horizontalLength() < WAKE_LINEAR_THRESHOLD) {
                waterSettleTicks++;
                if (waterSettleTicks >= WATER_REQUIRED_SLEEP_TICKS) {
                    // Zero out only vertical — leave horizontal free for wave drift.
                    this.setVelocity(velocity.x, 0.0D, velocity.z);
                }
            } else {
                waterSettleTicks = 0;
            }
            return;
        }

        // Reset water counter when leaving fluid.
        waterSettleTicks = 0;

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
                if (!this.getBoundingBox().intersects(other.getBoundingBox())) return;
                if (this.shipControlled && other instanceof PlayerEntity) return;
                if (this.shipControlled && other instanceof GravityBlockEntity otherBlock) {
                    UUID thisShip = this.linkedShipUuid;
                    UUID otherShip = otherBlock.getLinkedShipUuid();
                    if (thisShip != null && thisShip.equals(otherShip)) return;
                }
                if (other instanceof GravityBlockEntity) {
                    Box thisBox = this.getBoundingBox();
                    Box otherBox = other.getBoundingBox();
                    if (this.applyFloatingStackShear((GravityBlockEntity) other, thisBox, otherBox)) return;
                }
                if (other.getBoundingBox().minY >= this.getBoundingBox().maxY - 0.02D) return;
                if (this.isTopSupportedPlayer(other)) return;

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
                    double pushForce = 0.035 + (0.045 * collisionStrictness);
                    double pushX = deltaX * pushForce;
                    double pushZ = deltaZ * pushForce;
                    if (!other.hasPassengers() && other.isPushable()) {
                        double otherScale = other instanceof PlayerEntity ? (1.05 + (0.20 * collisionStrictness)) : (0.65 + (0.20 * collisionStrictness));
                        other.addVelocity(pushX * otherScale, 0.0, pushZ * otherScale);
                    }
                    if (!this.hasPassengers() && this.isPushable()) {
                        double recoilScale = MathHelper.clamp(0.22 - (0.10 * collisionStrictness), 0.06, 0.22);
                        this.addVelocity(-pushX * recoilScale, 0.0, -pushZ * recoilScale);
                    }
                }
            }
        }
    }

    @Override
    public void onPlayerCollision(PlayerEntity player) {
        if (this.isTopSupportedPlayer(player)) return;
        this.pushAwayFrom(player);
    }

    @Override
    public boolean isPushable() { return true; }

    @Override
    public boolean isCollidable() { return true; }

    @Override
    public boolean canHit() { return true; }

    @Override
    public boolean damage(DamageSource source, float amount) {
        ServerWorld world = this.getWorld() instanceof ServerWorld serverWorld ? serverWorld : null;
        if (world == null) return false;
        boolean value = false;
        if (!world.isClient) {
            if (source.getAttacker() instanceof ServerPlayerEntity player) {
                if (!player.isCreative()) {
                    if (lastDamageTick == this.age) return true;
                    lastDamageTick = this.age;
                    BlockPos pos = this.getBlockPos();
                    BlockState state = this.getBlockState();
                    if (state == null) state = BlockStateRegistry.getDefaultStateFor("air");
                    SoundEvent hitSound = state.getSoundGroup().getHitSound();
                    SoundEvent breakSound = state.getSoundGroup().getBreakSound();
                    world.playSound(null, pos, hitSound, SoundCategory.BLOCKS, 1.0F, 1.0F);
                    float hardness = state.getHardness(world, pos);
                    if (hardness < 0.0f) return false;
                    hardness = Math.max(hardness, 0.25f);
                    ItemStack tool = player.getMainHandStack();
                    boolean canHarvest = !state.isToolRequired() || player.canHarvest(state);
                    float breakingSpeed = player.getBlockBreakingSpeed(state);
                    if (breakingSpeed <= 0.0f) return false;
                    float vanillaDelta = breakingSpeed / hardness / (canHarvest ? 30.0f : 100.0f);
                    float progressIncrement = vanillaDelta;
                    if (!tool.isEmpty()) {
                        String toolPath = Registries.ITEM.getId(tool.getItem()).getPath();
                        progressIncrement *= ToolStrength.getToolMultiplier(toolPath);
                    } else {
                        if (HandMineableBlocks.isHandMineable(this.getBlockIdString())) {
                            progressIncrement *= 1.15f;
                        } else {
                            progressIncrement *= 0.55f;
                        }
                    }
                    progressIncrement = MathHelper.clamp(progressIncrement, 0.0f, 0.25f);
                    if (tool.isEmpty()) progressIncrement = Math.max(progressIncrement, 0.0012f);
                    this.miningProgress += progressIncrement;
                    this.getDataTracker().set(MINING_PROGRESS, this.miningProgress);
                    if (this.miningProgress >= MINING_THRESHOLD) {
                        world.playSound(null, pos, breakSound, SoundCategory.BLOCKS, 1.0F, 1.0F);
                        List<ItemStack> drops = Block.getDroppedStacks(state, world, pos, null, player, tool);
                        if (!drops.isEmpty()) {
                            for (ItemStack drop : drops) ItemScatterer.spawn(world, pos.getX(), pos.getY(), pos.getZ(), drop);
                        }
                        this.discard();
                        miningProgress = 0.0f;
                        this.getDataTracker().set(MINING_PROGRESS, this.miningProgress);
                        value = true;
                    }
                } else {
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
        if (id == null || id.isBlank()) return "";
        String normalized = id.toLowerCase();
        if (normalized.contains(":")) normalized = normalized.substring(normalized.indexOf(':') + 1);
        return normalized;
    }

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
        nbt.putFloat("WaveMetric", this.getTrackedWaveMetric());
        nbt.putFloat("PlayerLoad", this.getTrackedPlayerLoad());
        nbt.putDouble("Weight", this.weight);
        nbt.putInt("SettleTicks", this.settleTicks);
        nbt.putString("BlockId", this.getBlockIdString());
        if (this.linkedShipUuid != null) nbt.putUuid("LinkedShipUuid", this.linkedShipUuid);
        if (this.blockState != null) nbt.put("BlockState", NbtHelper.fromBlockState(this.blockState));
    }

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
        BlockState restoredState = null;
        if (nbt.contains("BlockId")) restoredState = BlockStateRegistry.getDefaultStateFor(nbt.getString("BlockId"));
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
        this.smoothedPlayerLoad = nbt.contains("PlayerLoad") ? nbt.getFloat("PlayerLoad") : 0.0f;
        this.lastWaveMetric = nbt.contains("WaveMetric") ? nbt.getFloat("WaveMetric") : 0.0f;
        this.linkedShipUuid = nbt.containsUuid("LinkedShipUuid") ? nbt.getUuid("LinkedShipUuid") : null;
        this.getDataTracker().set(WAVE_METRIC, this.lastWaveMetric);
        this.getDataTracker().set(PLAYER_LOAD, this.smoothedPlayerLoad);
    }

    public Vec3d getCenterOfMassOffset() { return centerOfMassOffset; }
    public void setCenterOfMassOffset(Vec3d centerOfMassOffset) { this.centerOfMassOffset = centerOfMassOffset; }
    public float getRotationAngle() { return rotationAngle; }
    public void setRotationAngle(float rotationAngle) { this.rotationAngle = rotationAngle; }
    public float getPreviousRotationAngle() { return previousRotationAngle; }
    public void setPreviousRotationAngle(float previousRotationAngle) { this.previousRotationAngle = previousRotationAngle; }
    public float getAngularVelocity() { return angularVelocity; }
    public void setAngularVelocity(float angularVelocity) { this.angularVelocity = angularVelocity; }
    public float getAngularMomentum() { return angularMomentum; }
    public void setAngularMomentum(float angularMomentum) { this.angularMomentum = angularMomentum; }
    public double getWeight() { return weight; }
    public void setWeight(double weight) { this.weight = weight; }

    public void setShipControlledPosition(Vec3d targetPos) {
        this.setShipControlledTransform(targetPos, this.getYaw());
    }

    public void setShipControlledTransform(Vec3d targetPos, float shipYaw) {
        this.shipControlled = true;
        this.setNoGravity(true);
        this.setVelocity(Vec3d.ZERO);
        this.angularVelocity = 0.0f;
        this.pitchAngularVelocity = 0.0f;
        this.rollAngularVelocity = 0.0f;
        this.rotationAngle = 0.0f;
        this.previousRotationAngle = 0.0f;
        this.roll = 0.0f;
        this.setRotation(shipYaw, 0.0f);
        this.getDataTracker().set(ROTATION, 0.0f);
        this.getDataTracker().set(PREVIOUS_ROTATION, 0.0f);
        this.getDataTracker().set(ROLL, 0.0f);
        this.setPosition(targetPos.x, targetPos.y, targetPos.z);
    }

    public void clearShipControl() {
        this.shipControlled = false;
        this.setNoGravity(false);
    }

    public void setLinkedShipUuid(UUID linkedShipUuid) { this.linkedShipUuid = linkedShipUuid; }
    public UUID getLinkedShipUuid() { return this.linkedShipUuid; }
    public boolean isShipControlled() { return this.shipControlled; }
}
