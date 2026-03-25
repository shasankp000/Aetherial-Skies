package net.shasankp000.Ship;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Entity.ShipBoatEntity;
import net.shasankp000.Entity.ShipCollisionPartEntity;
import net.shasankp000.Registry.ModEntityTypes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ShipCollisionLayerService {
    private static final double SEARCH_RADIUS = 96.0D;

    private ShipCollisionLayerService() {
    }

    public static void rebuildLayer(ShipBoatEntity ship) {
        if (!(ship.getWorld() instanceof ServerWorld world)) {
            return;
        }

        removeLayer(world, ship.getShipId(), ship.getBoundingBox().expand(SEARCH_RADIUS));
        ShipHullData hullData = ship.getHullData();
        List<LayerBox> layerBoxes = mergeLayerBoxes(hullData);
        for (LayerBox layerBox : layerBoxes) {
            ShipCollisionPartEntity part = new ShipCollisionPartEntity(ModEntityTypes.SHIP_COLLISION_PART_ENTITY, world);
            part.linkTo(ship.getShipId(), layerBox.localCenter(), layerBox.size());
            part.refreshPositionAndAngles(ship.getX(), ship.getY(), ship.getZ(), ship.getYaw(), ship.getPitch());
            world.spawnEntity(part);
        }
    }

    public static void ensureLayer(ShipBoatEntity ship) {
        if (!(ship.getWorld() instanceof ServerWorld world)) {
            return;
        }
        if (ship.age % 20 != 0) {
            return;
        }

        Box searchBox = ship.getBoundingBox().expand(SEARCH_RADIUS);
        List<ShipCollisionPartEntity> existing = world.getEntitiesByClass(
                ShipCollisionPartEntity.class,
                searchBox,
                part -> ship.getShipId().equals(part.getOwnerShipId())
        );
        int expected = mergeLayerBoxes(ship.getHullData()).size();
        if (existing.size() != expected) {
            rebuildLayer(ship);
        }
    }

    public static void removeLayer(ServerWorld world, UUID shipId, Box searchBox) {
        List<ShipCollisionPartEntity> parts = world.getEntitiesByClass(
                ShipCollisionPartEntity.class,
                searchBox,
                part -> shipId.equals(part.getOwnerShipId())
        );
        for (ShipCollisionPartEntity part : parts) {
            part.discard();
        }
    }

    private static List<LayerBox> mergeLayerBoxes(ShipHullData hullData) {
        Map<Integer, LayerAccumulator> layers = new HashMap<>();
        for (ShipCrateService.PackedBlock block : hullData.blocks()) {
            Vec3d o = block.localOffset();
            int layerY = (int) Math.floor(o.y);
            LayerAccumulator acc = layers.computeIfAbsent(layerY, y -> new LayerAccumulator());
            double minX = o.x - 0.5D;
            double maxX = o.x + 0.5D;
            double minZ = o.z - 0.5D;
            double maxZ = o.z + 0.5D;
            acc.minX = Math.min(acc.minX, minX);
            acc.maxX = Math.max(acc.maxX, maxX);
            acc.minZ = Math.min(acc.minZ, minZ);
            acc.maxZ = Math.max(acc.maxZ, maxZ);
        }

        List<LayerBox> boxes = new ArrayList<>(layers.size());
        for (Map.Entry<Integer, LayerAccumulator> entry : layers.entrySet()) {
            int layerY = entry.getKey();
            LayerAccumulator acc = entry.getValue();
            double sizeX = Math.max(0.6D, acc.maxX - acc.minX);
            double sizeY = 1.0D;
            double sizeZ = Math.max(0.6D, acc.maxZ - acc.minZ);
            Vec3d center = new Vec3d(
                    (acc.minX + acc.maxX) * 0.5D,
                    layerY + 0.5D,
                    (acc.minZ + acc.maxZ) * 0.5D
            );
            boxes.add(new LayerBox(center, new Vec3d(sizeX, sizeY, sizeZ)));
        }
        return boxes;
    }

    private record LayerBox(Vec3d localCenter, Vec3d size) {
    }

    private static final class LayerAccumulator {
        private double minX = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double minZ = Double.POSITIVE_INFINITY;
        private double maxZ = Double.NEGATIVE_INFINITY;
    }
}
