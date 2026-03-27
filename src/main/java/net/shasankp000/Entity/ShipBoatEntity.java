package net.shasankp000.Entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.shasankp000.Registry.ModEntityTypes;
import net.shasankp000.Ship.ShipCrateService;
import net.shasankp000.Ship.ShipHullData;
import net.shasankp000.Ship.Physics.ShipPhysicsEngine;
import net.shasankp000.Ship.Physics.ShipPhysicsState;

import java.util.HashSet;
import java.util.UUID;

public class ShipBoatEntity extends BoatEntity {
    private static final TrackedData<String> TRACKED_SHIP_ID =
            DataTracker.registerData(ShipBoatEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<NbtCompound> TRACKED_HULL_TAG =
            DataTracker.registerData(ShipBoatEntity.class, TrackedDataHandlerRegistry.NBT_COMPOUND);

    private UUID shipId = UUID.randomUUID();
    private NbtCompound hullTag = new NbtCompound();
    private ShipHullData cachedHullData = null;

    private ShipPhysicsEngine physicsEngine = null;
    private EntityDimensions cachedDimensions = EntityDimensions.changing(1.375f, 0.5625f);
    private ShipHullData.HullBounds cachedBounds = null;
    private int cachedLayerCount = -1;
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

        HashSet<Integer> layers = new HashSet<>();
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

        ShipHullData hull = getHullData();
        if (hull == null || hull.blocks().isEmpty()) {
            cachedDimensions = EntityDimensions.changing(1.375f, 0.5625f);
            cachedBounds = null;
            physicsEngine = null;
            this.calculateDimensions();
            return;
        }

        cachedBounds = hull.computeBounds();

        // Keep AABB at vanilla boat size; deck walking handled by dragger in Phase 2.
        cachedDimensions = EntityDimensions.changing(1.375f, 0.5625f);

        ShipPhysicsState newState = new ShipPhysicsState();
        newState.position = this.getPos();
        newState.velocity = this.getVelocity();
        newState.yaw = this.getYaw();
        newState.mass = Math.max(1.0f, hull.mass());

        physicsEngine = new ShipPhysicsEngine(newState, this.getWorld(), hull.totalDisplacedVolume());
        physicsEngine.setHullBounds(cachedBounds);
        physicsEngine.setHullData(hull);

        this.calculateDimensions();
    }

    @Override
    public boolean collidesWith(Entity other) {
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

    @Override
    protected void updatePassengerPosition(Entity passenger, Entity.PositionUpdater positionUpdater) {
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

    @Override
    public void tick() {
        // BoatEntity.tick() is allowed to run its first half via BoatEntityMixin
        // (cancelled at updatePaddles). That means Entity.baseTick() has already
        // been called by BoatEntity.tick() -> super.tick() before we get here.
        // Do NOT call invokeBaseTick() again — it would double-tick base entity logic
        // and corrupt interaction/flag state, preventing passenger mounting.

        // Reposition any current passengers each tick.
        for (Entity passenger : this.getPassengerList()) {
            this.updatePassengerPosition(passenger, Entity::setPosition);
        }

        if (!this.getWorld().isClient() && physicsEngine != null) {
            physicsEngine.syncFrom(this);
            physicsEngine.tick();
            physicsEngine.applyTo(this);
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
    }
}
