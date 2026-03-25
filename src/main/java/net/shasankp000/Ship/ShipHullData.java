package net.shasankp000.Ship;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record ShipHullData(
        UUID shipId,
        float mass,
        Vec3d helmOffset,
        float helmYawDegrees,
        float effectiveRelativeDensity,
        float buoyancyAssist,
        float totalDisplacedVolume,
        List<ShipCrateService.PackedBlock> blocks
) {
    private static final String BLOCKS_TAG = "Blocks";

    public NbtCompound toCrateTag() {
        NbtCompound shipTag = new NbtCompound();
        shipTag.putUuid("ShipId", shipId);
        shipTag.putFloat("Mass", mass);
        shipTag.putDouble("HelmOffsetX", helmOffset.x);
        shipTag.putDouble("HelmOffsetY", helmOffset.y);
        shipTag.putDouble("HelmOffsetZ", helmOffset.z);
        shipTag.putFloat("HelmYawDegrees", helmYawDegrees);
        shipTag.putFloat("EffectiveRelativeDensity", effectiveRelativeDensity);
        shipTag.putFloat("BuoyancyAssist", buoyancyAssist);
        shipTag.putFloat("TotalDisplacedVolume", totalDisplacedVolume);

        NbtList blocksList = new NbtList();
        for (ShipCrateService.PackedBlock block : blocks) {
            NbtCompound blockTag = new NbtCompound();
            blockTag.putString("BlockId", block.blockId());
            blockTag.putDouble("OffsetX", block.localOffset().x);
            blockTag.putDouble("OffsetY", block.localOffset().y);
            blockTag.putDouble("OffsetZ", block.localOffset().z);
            blockTag.putBoolean("IsHelm", block.isHelm());
            blocksList.add(blockTag);
        }
        shipTag.put(BLOCKS_TAG, blocksList);
        return shipTag;
    }

    public NbtCompound toEntityTag() {
        NbtCompound entityTag = new NbtCompound();
        entityTag.putUuid("ShipId", shipId);
        entityTag.put(BLOCKS_TAG, toCrateTag().getList(BLOCKS_TAG, 10).copy());
        entityTag.putFloat("Mass", mass);
        entityTag.putDouble("HelmOffsetX", helmOffset.x);
        entityTag.putDouble("HelmOffsetY", helmOffset.y);
        entityTag.putDouble("HelmOffsetZ", helmOffset.z);
        entityTag.putFloat("HelmYawDegrees", helmYawDegrees);
        entityTag.putFloat("EffectiveRelativeDensity", effectiveRelativeDensity);
        entityTag.putFloat("BuoyancyAssist", buoyancyAssist);
        entityTag.putFloat("TotalDisplacedVolume", totalDisplacedVolume);
        return entityTag;
    }

    public static ShipHullData fromCrateTag(NbtCompound shipTag) {
        UUID shipId = shipTag.containsUuid("ShipId") ? shipTag.getUuid("ShipId") : UUID.randomUUID();
        float mass = shipTag.getFloat("Mass");
        Vec3d helmOffset = new Vec3d(
                shipTag.getDouble("HelmOffsetX"),
                shipTag.getDouble("HelmOffsetY"),
                shipTag.getDouble("HelmOffsetZ")
        );
        float helmYawDegrees = shipTag.contains("HelmYawDegrees", 5) ? shipTag.getFloat("HelmYawDegrees") : 0.0f;
        float effectiveRelativeDensity = shipTag.getFloat("EffectiveRelativeDensity");
        float buoyancyAssist = shipTag.getFloat("BuoyancyAssist");
        float totalDisplacedVolume = shipTag.getFloat("TotalDisplacedVolume");

        NbtList blocksTag = shipTag.getList(BLOCKS_TAG, 10);
        List<ShipCrateService.PackedBlock> blocks = new ArrayList<>(blocksTag.size());
        for (int i = 0; i < blocksTag.size(); i++) {
            NbtCompound blockTag = blocksTag.getCompound(i);
            blocks.add(new ShipCrateService.PackedBlock(
                    blockTag.getString("BlockId"),
                    new Vec3d(
                            blockTag.getDouble("OffsetX"),
                            blockTag.getDouble("OffsetY"),
                            blockTag.getDouble("OffsetZ")
                    ),
                    blockTag.getBoolean("IsHelm")
            ));
        }

        return new ShipHullData(
                shipId,
                mass,
                helmOffset,
                helmYawDegrees,
                effectiveRelativeDensity,
                buoyancyAssist,
                totalDisplacedVolume,
                List.copyOf(blocks)
        );
    }

    public static ShipHullData fromEntityTag(NbtCompound entityTag) {
        return fromCrateTag(entityTag);
    }

    public HullBounds computeBounds() {
        if (blocks.isEmpty()) {
            return new HullBounds(-0.5D, 0.0D, -0.5D, 0.5D, 0.5625D, 0.5D);
        }

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        for (ShipCrateService.PackedBlock block : blocks) {
            Vec3d offset = block.localOffset();
            minX = Math.min(minX, offset.x - 0.5D);
            maxX = Math.max(maxX, offset.x + 0.5D);
            minY = Math.min(minY, offset.y);
            maxY = Math.max(maxY, offset.y + 1.0D);
            minZ = Math.min(minZ, offset.z - 0.5D);
            maxZ = Math.max(maxZ, offset.z + 0.5D);
        }

        return new HullBounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public record HullBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public double widthX() {
            return maxX - minX;
        }

        public double widthZ() {
            return maxZ - minZ;
        }

        public double height() {
            return maxY - minY;
        }
    }
}
