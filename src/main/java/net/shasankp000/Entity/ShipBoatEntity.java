package net.shasankp000.Entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.Entity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.shasankp000.Registry.ModEntityTypes;
import net.shasankp000.Ship.ShipCollisionLayerService;
import net.shasankp000.Ship.ShipCrateService;
import net.shasankp000.Ship.ShipHullData;

import java.util.UUID;

public class ShipBoatEntity extends BoatEntity {
    private static final TrackedData<String> TRACKED_SHIP_ID =
            DataTracker.registerData(ShipBoatEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<NbtCompound> TRACKED_HULL_TAG =
            DataTracker.registerData(ShipBoatEntity.class, TrackedDataHandlerRegistry.NBT_COMPOUND);

    private UUID shipId = UUID.randomUUID();
    private NbtCompound hullTag = new NbtCompound();
    private ShipHullData cachedHullData = null;

    private EntityDimensions cachedDimensions = EntityDimensions.changing(1.375f, 0.5625f);
    private ShipHullData.HullBounds cachedBounds = null;
    private float waterlineOffset = 0.0f;
    private int cachedLayerCount = -1;
    private float cachedSubmersion = 0.0f;
    private int submersionCheckCooldown = 0;
    private int lastRebuildAge = -200;

    public ShipBoatEntity(EntityType<? extends ShipBoatEntity> entityType, World world) {
        super(entityType, world);
    }

    public ShipBoatEntity(World world, double x, double y, double z) {
        this(ModEntityTypes.SHIP_BOAT_ENTITY, world);
        this.setPosition(x, y, z);
        this.setVelocity(0.0, 0.0, 0.0);
        this.prevX = x;
        this.prevY = y;
        this.prevZ = z;
    }

    public UUID getShipId() {
        return shipId;
    }

    public ShipHullData getHullData() {
        if (cachedHullData != null) {
            return cachedHullData;
        }

        NbtCompound tag = this.getWorld().isClient()
                ? this.getDataTracker().get(TRACKED_HULL_TAG)
                : hullTag;
        cachedHullData = ShipHullData.fromEntityTag(tag);
        return cachedHullData;
    }

    public void setHullData(ShipHullData hullData) {
        this.shipId = hullData.shipId();
        this.cachedHullData = hullData;
        this.hullTag = hullData.toEntityTag();
        this.getDataTracker().set(TRACKED_SHIP_ID, this.shipId.toString());
        this.getDataTracker().set(TRACKED_HULL_TAG, this.hullTag.copy());
        recalculateDimensions();
    }

    public int getExpectedLayerCount() {
        if (cachedLayerCount >= 0) {
            return cachedLayerCount;
        }

        ShipHullData hull = getHullData();
        if (hull.blocks().isEmpty()) {
            cachedLayerCount = 0;
            return 0;
        }

        java.util.HashSet<Integer> layers = new java.util.HashSet<>();
        for (ShipCrateService.PackedBlock block : hull.blocks()) {
            layers.add((int) Math.floor(block.localOffset().y));
        }
        cachedLayerCount = layers.size();
        return cachedLayerCount;
    }

    public boolean canRebuildLayer() {
        if (this.age - lastRebuildAge < 100) {
            return false;
        }
        lastRebuildAge = this.age;
        return true;
    }

    @Override
    public EntityDimensions getDimensions(EntityPose pose) {
        return cachedDimensions;
    }

    private void recalculateDimensions() {
        cachedLayerCount = -1;
        cachedSubmersion = 0.0f;
        submersionCheckCooldown = 0;

        ShipHullData hull = getHullData();
        if (hull.blocks().isEmpty()) {
            cachedDimensions = EntityDimensions.changing(1.375f, 0.5625f);
            cachedBounds = null;
            waterlineOffset = 0.0f;
            this.calculateDimensions();
            return;
        }

        cachedBounds = hull.computeBounds();

        // Keep ship core collider at vanilla size so it cannot block itself against its own collision parts.
        cachedDimensions = EntityDimensions.changing(1.375f, 0.5625f);
        waterlineOffset = 0.0f;
        this.calculateDimensions();
    }

    @Override
    public boolean collidesWith(Entity other) {
        if (other instanceof ShipCollisionPartEntity part) {
            UUID partOwner = part.getOwnerShipId();
            if (partOwner != null && partOwner.equals(this.shipId)) {
                return false;
            }
        }
        return super.collidesWith(other);
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        if (this.shipId == null) {
            this.shipId = UUID.randomUUID();
        }
        if (this.hullTag == null) {
            this.hullTag = new NbtCompound();
        }
        this.dataTracker.startTracking(TRACKED_SHIP_ID, this.shipId.toString());
        this.dataTracker.startTracking(TRACKED_HULL_TAG, this.hullTag.copy());
    }

    @Override
    public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        if (TRACKED_SHIP_ID.equals(data)) {
            try {
                this.shipId = UUID.fromString(this.getDataTracker().get(TRACKED_SHIP_ID));
            } catch (IllegalArgumentException ignored) {
                this.shipId = UUID.randomUUID();
            }
            return;
        }
        if (TRACKED_HULL_TAG.equals(data)) {
            this.hullTag = this.getDataTracker().get(TRACKED_HULL_TAG).copy();
            this.cachedHullData = null;
            recalculateDimensions();
        }
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (this.shipId == null) {
            this.shipId = UUID.randomUUID();
        }
        if (this.hullTag == null) {
            this.hullTag = new NbtCompound();
        }
        nbt.putUuid("ShipId", shipId);
        nbt.put("ShipHull", hullTag.copy());
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.containsUuid("ShipId")) {
            shipId = nbt.getUuid("ShipId");
        }
        if (nbt.contains("ShipHull", 10)) {
            hullTag = nbt.getCompound("ShipHull").copy();
        } else {
            hullTag = new NbtCompound();
        }
        this.cachedHullData = null;
        this.getDataTracker().set(TRACKED_SHIP_ID, shipId.toString());
        this.getDataTracker().set(TRACKED_HULL_TAG, hullTag.copy());
        recalculateDimensions();
    }

    private void applyHullBuoyancy() {
        if (cachedBounds == null) {
            return;
        }

        ShipHullData hull = getHullData();
        float effectiveDensity = Math.max(0.05f, hull.effectiveRelativeDensity());
        float buoyancyAssist = hull.buoyancyAssist();
        float submersionRatio = calculateHullSubmersion();

        double buoyancyForce = 0.0D;

        if (submersionRatio > 0.0f) {
            float targetSubmersion = Math.min(0.9f, effectiveDensity);
            double displacement = submersionRatio - targetSubmersion;
            // super.tick() already applies vanilla boat forces; this is a corrective hull bias, not a second gravity pass.
            double restoring = displacement * 0.08D;
            double assistLift = buoyancyAssist * 0.015D;
            buoyancyForce = restoring + assistLift;
        }

        Vec3d currentVel = this.getVelocity();
        this.setVelocity(currentVel.x, currentVel.y + buoyancyForce, currentVel.z);

        Vec3d updated = this.getVelocity();
        if (Math.abs(updated.y) < 0.003D) {
            this.setVelocity(updated.x, 0.0D, updated.z);
        } else {
            this.setVelocity(updated.x, updated.y * 0.92D, updated.z);
        }
    }

    private float calculateHullSubmersion() {
        if (submersionCheckCooldown > 0) {
            submersionCheckCooldown--;
            return cachedSubmersion;
        }
        submersionCheckCooldown = 3;

        if (cachedBounds == null) {
            cachedSubmersion = 0.0f;
            return 0.0f;
        }

        ShipHullData hull = getHullData();
        if (hull.blocks().isEmpty()) {
            cachedSubmersion = 0.0f;
            return 0.0f;
        }

        int submergedBlocks = 0;
        int totalBlocks = hull.blocks().size();

        double yawRad = Math.toRadians(-this.getYaw());
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);

        for (ShipCrateService.PackedBlock block : hull.blocks()) {
            Vec3d offset = block.localOffset();
            double worldX = this.getX() + (offset.x * cos - offset.z * sin);
            double worldY = this.getY() + offset.y - waterlineOffset;
            double worldZ = this.getZ() + (offset.x * sin + offset.z * cos);

            BlockPos blockPos = BlockPos.ofFloored(worldX, worldY, worldZ);
            if (this.getWorld().getFluidState(blockPos).isIn(FluidTags.WATER)) {
                submergedBlocks++;
            }
        }

        cachedSubmersion = (float) submergedBlocks / (float) totalBlocks;
        return cachedSubmersion;
    }

    @Override
    protected void updatePassengerPosition(net.minecraft.entity.Entity passenger, net.minecraft.entity.Entity.PositionUpdater positionUpdater) {
        if (!this.hasPassenger(passenger)) {
            return;
        }

        ShipHullData hull = getHullData();
        Vec3d helmOffset = hull.helmOffset();
        if (helmOffset.equals(Vec3d.ZERO) || hull.blocks().isEmpty()) {
            super.updatePassengerPosition(passenger, positionUpdater);
            return;
        }

        double yawRad = Math.toRadians(-this.getYaw());
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);

        double riderX = this.getX() + (helmOffset.x * cos - helmOffset.z * sin);
        double riderZ = this.getZ() + (helmOffset.x * sin + helmOffset.z * cos);
        double riderY = this.getY() + helmOffset.y + 0.7D;

        positionUpdater.accept(passenger, riderX, riderY, riderZ);
        passenger.setBodyYaw(this.getYaw());
    }

    private void clampToTerrain() {
        if (cachedBounds == null) {
            return;
        }

        double lowestHullY = this.getY() + cachedBounds.minY();
        BlockPos checkPos = BlockPos.ofFloored(this.getX(), lowestHullY - 0.1D, this.getZ());
        if (!this.getWorld().getBlockState(checkPos).isAir() && this.getWorld().getFluidState(checkPos).isEmpty()) {
            double pushY = checkPos.getY() + 1.0D - cachedBounds.minY() + 0.05D;
            if (pushY > this.getY()) {
                Vec3d vel = this.getVelocity();
                this.setPosition(this.getX(), pushY, this.getZ());
                this.setVelocity(vel.x, Math.max(0.0D, vel.y), vel.z);
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        applyHullBuoyancy();
        if (!this.getWorld().isClient()) {
            ShipCollisionLayerService.ensureLayer(this);
            clampToTerrain();
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!this.getWorld().isClient() && this.getWorld() instanceof ServerWorld world) {
            ShipCollisionLayerService.removeLayer(world, this.getShipId(), this.getBoundingBox().expand(16.0D));
        }
        super.remove(reason);
    }
}

