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

/**
 * Entity type registry.
 * ShipBoatEntity has been removed — ships are now rendered as real blocks
 * in the ship_storage dimension with a client-side transform overlay.
 */
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

    private ModEntityTypes() {}

    public static void register() {
        // static initialiser side-effects only
    }
}
