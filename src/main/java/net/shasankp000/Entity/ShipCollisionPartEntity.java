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
    private static final TrackedData<Float> TRACKED_SIZE_X =
            DataTracker.registerData(ShipCollisionPartEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> TRACKED_SIZE_Y =
            DataTracker.registerData(ShipCollisionPartEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> TRACKED_SIZE_Z =
            DataTracker.registerData(ShipCollisionPartEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private UUID ownerShipId;
    private Vec3d localOffset = Vec3d.ZERO;
    private Vec3d halfSize = new Vec3d(0.5D, 0.5D, 0.5D);

    private ShipBoatEntity cachedOwner;
    private int ownerEntityId = -1;
    private int ownerSearchCooldown = 0;
    private int missingOwnerTicks = 0;

    public ShipCollisionPartEntity(EntityType<? extends ShipCollisionPartEntity> type, World world) {
        super(type, world);
        this.noClip = true;
        this.setNoGravity(true);
    }

    @Override
    protected void initDataTracker() {
        this.dataTracker.startTracking(TRACKED_OWNER_SHIP_ID, "");
        this.dataTracker.startTracking(TRACKED_LOCAL_X, 0.0f);
        this.dataTracker.startTracking(TRACKED_LOCAL_Y, 0.0f);
        this.dataTracker.startTracking(TRACKED_LOCAL_Z, 0.0f);
        this.dataTracker.startTracking(TRACKED_SIZE_X, 1.0f);
        this.dataTracker.startTracking(TRACKED_SIZE_Y, 1.0f);
        this.dataTracker.startTracking(TRACKED_SIZE_Z, 1.0f);
    }

    public void linkTo(ShipBoatEntity owner, Vec3d offset, Vec3d size) {
        this.cachedOwner = owner;
        this.ownerEntityId = owner.getId();
        linkTo(owner.getShipId(), offset, size);
    }

    public void linkTo(UUID shipId, Vec3d offset, Vec3d size) {
        this.ownerShipId = shipId;
        this.localOffset = offset;
        this.halfSize = new Vec3d(size.x * 0.5D, size.y * 0.5D, size.z * 0.5D);

        this.getDataTracker().set(TRACKED_OWNER_SHIP_ID, shipId.toString());
        this.getDataTracker().set(TRACKED_LOCAL_X, (float) offset.x);
        this.getDataTracker().set(TRACKED_LOCAL_Y, (float) offset.y);
        this.getDataTracker().set(TRACKED_LOCAL_Z, (float) offset.z);
        this.getDataTracker().set(TRACKED_SIZE_X, (float) size.x);
        this.getDataTracker().set(TRACKED_SIZE_Y, (float) size.y);
        this.getDataTracker().set(TRACKED_SIZE_Z, (float) size.z);
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
            return;
        }

        if (TRACKED_SIZE_X.equals(data) || TRACKED_SIZE_Y.equals(data) || TRACKED_SIZE_Z.equals(data)) {
            halfSize = new Vec3d(
                    this.getDataTracker().get(TRACKED_SIZE_X) * 0.5D,
                    this.getDataTracker().get(TRACKED_SIZE_Y) * 0.5D,
                    this.getDataTracker().get(TRACKED_SIZE_Z) * 0.5D
            );
        }
    }

    @Override
    public void tick() {
        // Skip vanilla entity tick work; this entity is manually positioned each tick.
        this.age++;
        if (this.getWorld().isClient()) {
            return;
        }

        if (ownerShipId == null) {
            this.discard();
            return;
        }

        ShipBoatEntity ship = resolveOwner();
        if (ship == null) {
            missingOwnerTicks++;
            if (missingOwnerTicks > 120) {
                this.discard();
            }
            return;
        }
        missingOwnerTicks = 0;

        double yawRad = Math.toRadians(-ship.getYaw());
        double cos = Math.cos(yawRad);
        double sin = Math.sin(yawRad);
        double rotatedX = localOffset.x * cos - localOffset.z * sin;
        double rotatedZ = localOffset.x * sin + localOffset.z * cos;

        double centerX = ship.getX() + rotatedX;
        double centerY = ship.getY() + localOffset.y;
        double centerZ = ship.getZ() + rotatedZ;

        this.setPosition(centerX, centerY, centerZ);
        this.setYaw(ship.getYaw());
        this.setPitch(ship.getPitch());
        this.setBoundingBox(new Box(
                centerX - halfSize.x,
                centerY - halfSize.y,
                centerZ - halfSize.z,
                centerX + halfSize.x,
                centerY + halfSize.y,
                centerZ + halfSize.z
        ));
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        if (nbt.containsUuid("OwnerShipId")) {
            ownerShipId = nbt.getUuid("OwnerShipId");
        } else {
            ownerShipId = null;
        }

        localOffset = new Vec3d(
                nbt.getDouble("LocalX"),
                nbt.getDouble("LocalY"),
                nbt.getDouble("LocalZ")
        );

        Vec3d size = new Vec3d(
                nbt.contains("SizeX", 6) ? nbt.getDouble("SizeX") : 1.0D,
                nbt.contains("SizeY", 6) ? nbt.getDouble("SizeY") : 1.0D,
                nbt.contains("SizeZ", 6) ? nbt.getDouble("SizeZ") : 1.0D
        );
        halfSize = new Vec3d(size.x * 0.5D, size.y * 0.5D, size.z * 0.5D);

        ownerEntityId = nbt.contains("OwnerEntityId", 3) ? nbt.getInt("OwnerEntityId") : -1;
        cachedOwner = null;
        ownerSearchCooldown = 0;
        missingOwnerTicks = 0;

        if (ownerShipId != null) {
            this.getDataTracker().set(TRACKED_OWNER_SHIP_ID, ownerShipId.toString());
        } else {
            this.getDataTracker().set(TRACKED_OWNER_SHIP_ID, "");
        }
        this.getDataTracker().set(TRACKED_LOCAL_X, (float) localOffset.x);
        this.getDataTracker().set(TRACKED_LOCAL_Y, (float) localOffset.y);
        this.getDataTracker().set(TRACKED_LOCAL_Z, (float) localOffset.z);
        this.getDataTracker().set(TRACKED_SIZE_X, (float) size.x);
        this.getDataTracker().set(TRACKED_SIZE_Y, (float) size.y);
        this.getDataTracker().set(TRACKED_SIZE_Z, (float) size.z);
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        if (ownerShipId != null) {
            nbt.putUuid("OwnerShipId", ownerShipId);
        }
        nbt.putDouble("LocalX", localOffset.x);
        nbt.putDouble("LocalY", localOffset.y);
        nbt.putDouble("LocalZ", localOffset.z);
        nbt.putDouble("SizeX", halfSize.x * 2.0D);
        nbt.putDouble("SizeY", halfSize.y * 2.0D);
        nbt.putDouble("SizeZ", halfSize.z * 2.0D);
        if (ownerEntityId >= 0) {
            nbt.putInt("OwnerEntityId", ownerEntityId);
        }
    }

    private ShipBoatEntity resolveOwner() {
        if (cachedOwner != null
                && !cachedOwner.isRemoved()
                && ownerShipId != null
                && ownerShipId.equals(cachedOwner.getShipId())) {
            return cachedOwner;
        }

        if (ownerEntityId >= 0) {
            Entity byId = this.getWorld().getEntityById(ownerEntityId);
            if (byId instanceof ShipBoatEntity ship
                    && ownerShipId != null
                    && ownerShipId.equals(ship.getShipId())) {
                cachedOwner = ship;
                return ship;
            }
        }

        if (ownerSearchCooldown > 0) {
            ownerSearchCooldown--;
            return null;
        }
        ownerSearchCooldown = 100;

        List<ShipBoatEntity> owners = this.getWorld().getEntitiesByClass(
                ShipBoatEntity.class,
                this.getBoundingBox().expand(16.0D),
                ship -> ownerShipId != null && ownerShipId.equals(ship.getShipId())
        );

        if (!owners.isEmpty()) {
            ShipBoatEntity owner = owners.get(0);
            cachedOwner = owner;
            ownerEntityId = owner.getId();
            return owner;
        }

        return null;
    }

    @Override
    public boolean collidesWith(Entity other) {
        if (other instanceof ShipBoatEntity ship) {
            return !(ownerShipId != null && ownerShipId.equals(ship.getShipId()));
        }
        if (other instanceof ShipCollisionPartEntity part) {
            return !(ownerShipId != null && ownerShipId.equals(part.getOwnerShipId()));
        }
        return super.collidesWith(other);
    }

    @Override
    public boolean isCollidable() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void pushAwayFrom(Entity entity) {
        // Collision part should never push anything.
    }

    protected void pushAway(Entity entity) {
        // Collision part should never be part of push resolution.
    }
}
