package net.shasankp000.Entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.shasankp000.Registry.ModEntityTypes;
import net.shasankp000.Ship.ShipCollisionLayerService;
import net.shasankp000.Ship.ShipHullData;

import java.util.UUID;

public class ShipBoatEntity extends BoatEntity {
    private static final TrackedData<String> TRACKED_SHIP_ID =
            DataTracker.registerData(ShipBoatEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<NbtCompound> TRACKED_HULL_TAG =
            DataTracker.registerData(ShipBoatEntity.class, TrackedDataHandlerRegistry.NBT_COMPOUND);

    private UUID shipId = UUID.randomUUID();
    private NbtCompound hullTag = new NbtCompound();

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
        if (this.getWorld().isClient()) {
            return ShipHullData.fromEntityTag(this.getDataTracker().get(TRACKED_HULL_TAG));
        }
        return ShipHullData.fromEntityTag(hullTag);
    }

    public void setHullData(ShipHullData hullData) {
        this.shipId = hullData.shipId();
        this.hullTag = hullData.toEntityTag();
        this.getDataTracker().set(TRACKED_SHIP_ID, this.shipId.toString());
        this.getDataTracker().set(TRACKED_HULL_TAG, this.hullTag.copy());
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
        this.getDataTracker().set(TRACKED_SHIP_ID, shipId.toString());
        this.getDataTracker().set(TRACKED_HULL_TAG, hullTag.copy());
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.getWorld().isClient()) {
            ShipCollisionLayerService.ensureLayer(this);
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!this.getWorld().isClient() && this.getWorld() instanceof ServerWorld world) {
            ShipCollisionLayerService.removeLayer(world, this.getShipId(), this.getBoundingBox().expand(128.0D));
        }
        super.remove(reason);
    }
}
