package net.shasankp000.Registry;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.shasankp000.AetherialSkies;
import net.shasankp000.Entity.GravityBlockEntity;
import net.shasankp000.Entity.ShipBoatEntity;
import net.shasankp000.Entity.ShipCollisionPartEntity;

public final class ModEntityTypes {
    public static final EntityType<GravityBlockEntity> GRAVITY_BLOCK_ENTITY =
            Registry.register(
                    Registries.ENTITY_TYPE,
                    new Identifier(AetherialSkies.MOD_ID, "gravity_affected_block"),
                    FabricEntityTypeBuilder.create(SpawnGroup.MISC, GravityBlockEntity::new)
                            .dimensions(EntityDimensions.fixed(1.0f, 1.0f))
                            .trackRangeBlocks(128)
                            .trackedUpdateRate(1)
                            .build()
            );

    public static final EntityType<ShipBoatEntity> SHIP_BOAT_ENTITY =
            Registry.register(
                    Registries.ENTITY_TYPE,
                    new Identifier(AetherialSkies.MOD_ID, "ship_boat"),
                    FabricEntityTypeBuilder.<ShipBoatEntity>create(SpawnGroup.MISC, ShipBoatEntity::new)
                            .dimensions(EntityDimensions.fixed(1.375f, 0.5625f))
                            .trackRangeBlocks(128)
                            .trackedUpdateRate(10)
                            .build()
            );

    public static final EntityType<ShipCollisionPartEntity> SHIP_COLLISION_PART_ENTITY =
            Registry.register(
                    Registries.ENTITY_TYPE,
                    new Identifier(AetherialSkies.MOD_ID, "ship_collision_part"),
                    FabricEntityTypeBuilder.<ShipCollisionPartEntity>create(SpawnGroup.MISC, ShipCollisionPartEntity::new)
                            .dimensions(EntityDimensions.fixed(1.0f, 1.0f))
                            .trackRangeBlocks(64)
                            .trackedUpdateRate(2)
                            .build()
            );

    private ModEntityTypes() {
    }

    public static void register() {
        // Static registration side effects.
    }
}
