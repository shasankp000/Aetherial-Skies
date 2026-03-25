package net.shasankp000.Ship;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.shasankp000.Entity.ShipBoatEntity;
import net.shasankp000.Entity.ShipCollisionPartEntity;
import net.shasankp000.Registry.ModEntityTypes;

import java.util.List;
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
        for (ShipCrateService.PackedBlock block : hullData.blocks()) {
            ShipCollisionPartEntity part = new ShipCollisionPartEntity(ModEntityTypes.SHIP_COLLISION_PART_ENTITY, world);
            part.linkTo(ship.getShipId(), block.localOffset());
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
        int expected = ship.getHullData().blocks().size();
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
}

