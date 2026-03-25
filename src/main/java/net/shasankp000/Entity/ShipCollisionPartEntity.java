package net.shasankp000.Entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;
import java.util.UUID;

public class ShipCollisionPartEntity extends Entity {
    private static final TrackedData<String> TRACKED_OWNER_SHIP_ID =
            DataTracker.registerData(ShipCollisionPartEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<Float> TRACKED_LOCAL_X =
            DataTracker.registerData(ShipCollisionPartEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> TRACKED_LOCAL_Y =
            DataTracker.registerData(ShipCollisionPartEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> TRACKED_LOCAL_Z =
            DataTracker.registerData(ShipCollisionPartEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private UUID ownerShipId = null;
    private Vec3d localOffset = Vec3d.ZERO;

    public ShipCollisionPartEntity(EntityType<? extends ShipCollisionPartEntity> type, World world) {
        super(type, world);
        this.noClip = false;
        this.setNoGravity(true);
    }

    @Override
    protected void initDataTracker() {
        this.dataTracker.startTracking(TRACKED_OWNER_SHIP_ID, "");
        this.dataTracker.startTracking(TRACKED_LOCAL_X, 0.0f);
        this.dataTracker.startTracking(TRACKED_LOCAL_Y, 0.0f);
        this.dataTracker.startTracking(TRACKED_LOCAL_Z, 0.0f);
    }

    public void linkTo(UUID shipId, Vec3d offset) {
        this.ownerShipId = shipId;
        this.localOffset = offset;
        this.getDataTracker().set(TRACKED_OWNER_SHIP_ID, shipId.toString());
        this.getDataTracker().set(TRACKED_LOCAL_X, (float) offset.x);
        this.getDataTracker().set(TRACKED_LOCAL_Y, (float) offset.y);
        this.getDataTracker().set(TRACKED_LOCAL_Z, (float) offset.z);
    }

    public UUID getOwnerShipId() {
        return ownerShipId;
    }

    @Override
    public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        if (TRACKED_OWNER_SHIP_ID.equals(data)) {
            String id = this.getDataTracker().get(TRACKED_OWNER_SHIP_ID);
            if (id.isEmpty()) {
                ownerShipId = null;
            } else {
                try {
                    ownerShipId = UUID.fromString(id);
                } catch (IllegalArgumentException ignored) {
                    ownerShipId = null;
                }
            }
            return;
        }
        if (TRACKED_LOCAL_X.equals(data) || TRACKED_LOCAL_Y.equals(data) || TRACKED_LOCAL_Z.equals(data)) {
            localOffset = new Vec3d(
                    this.getDataTracker().get(TRACKED_LOCAL_X),
                    this.getDataTracker().get(TRACKED_LOCAL_Y),
                    this.getDataTracker().get(TRACKED_LOCAL_Z)
            );
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.getWorld().isClient()) {
            return;
        }
        if (ownerShipId == null) {
            this.discard();
            return;
        }

        List<ShipBoatEntity> owners = this.getWorld().getEntitiesByClass(
                ShipBoatEntity.class,
                this.getBoundingBox().expand(64.0D),
                ship -> ownerShipId.equals(ship.getShipId())
        );
        if (owners.isEmpty()) {
            this.discard();
            return;
        }

        ShipBoatEntity ship = owners.get(0);
        double yawRad = Math.toRadians(-ship.getYaw());
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        double rotatedX = (localOffset.x * cos) - (localOffset.z * sin);
        double rotatedZ = (localOffset.x * sin) + (localOffset.z * cos);

        double centerX = ship.getX() + rotatedX;
        double centerY = ship.getY() + localOffset.y;
        double centerZ = ship.getZ() + rotatedZ;
        this.setPosition(centerX, centerY, centerZ);
        this.setYaw(ship.getYaw());
        this.setBoundingBox(new Box(centerX - 0.5D, centerY, centerZ - 0.5D, centerX + 0.5D, centerY + 1.0D, centerZ + 0.5D));
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        if (nbt.containsUuid("OwnerShipId")) {
            ownerShipId = nbt.getUuid("OwnerShipId");
        }
        localOffset = new Vec3d(
                nbt.getDouble("LocalX"),
                nbt.getDouble("LocalY"),
                nbt.getDouble("LocalZ")
        );
        if (ownerShipId != null) {
            this.getDataTracker().set(TRACKED_OWNER_SHIP_ID, ownerShipId.toString());
        }
        this.getDataTracker().set(TRACKED_LOCAL_X, (float) localOffset.x);
        this.getDataTracker().set(TRACKED_LOCAL_Y, (float) localOffset.y);
        this.getDataTracker().set(TRACKED_LOCAL_Z, (float) localOffset.z);
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        if (ownerShipId != null) {
            nbt.putUuid("OwnerShipId", ownerShipId);
        }
        nbt.putDouble("LocalX", localOffset.x);
        nbt.putDouble("LocalY", localOffset.y);
        nbt.putDouble("LocalZ", localOffset.z);
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
        return false;
    }

    @Override
    public boolean collidesWith(Entity other) {
        if (other instanceof ShipBoatEntity || other instanceof ShipCollisionPartEntity) {
            return false;
        }
        return super.collidesWith(other);
    }
}

