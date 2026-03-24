package net.shasankp000;


import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shasankp000.Entity.GravityBlockEntity;
import net.shasankp000.Gravity.GravityData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;


public class AetherialSkies implements ModInitializer {
	public static final String MOD_ID = "aetherial-skies";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);


	private static final Set<BlockPos> blocksToCheck = new HashSet<>();


	public static Set<BlockPos> getBlocksToCheck() {
		return blocksToCheck;
	}

	// Register the custom entity type (Replacing FabricEntityTypeBuilder with EntityType.Builder)
	public static final EntityType<GravityBlockEntity> GRAVITY_BLOCK_ENTITY =
			Registry.register(
					Registries.ENTITY_TYPE,
					new Identifier(MOD_ID, "gravity_affected_block"),
					FabricEntityTypeBuilder.create(SpawnGroup.MISC, GravityBlockEntity::new)
							.dimensions(EntityDimensions.fixed(1.0f, 1.0f))
							.trackRangeBlocks(128)
							.trackedUpdateRate(1)
							.build()
			);

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Aetherial Skies...");

		// Example: register a tick event to process gravity checks
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			Set<BlockPos> toRemove = new HashSet<>();
			RegistryKey<World> worldRegistryKey = server.getCommandSource().getWorld().getRegistryKey();

			for (BlockPos pos : blocksToCheck) {
				BlockState state = server.getOverworld().getBlockState(pos);

				// Check if block is gravity-enabled
				if (GravityData.isGravityEnabled(state.getBlock())) {
					double weight = GravityData.getWeight(state.getBlock());


					BlockPos below = pos.down();
					if (server.getOverworld().getBlockState(below).isAir()) {
						server.getOverworld().removeBlock(pos, false);
						GravityBlockEntity gravityBlockEntity = new GravityBlockEntity(GRAVITY_BLOCK_ENTITY, server.getOverworld());
						gravityBlockEntity.setBlockState(state); // preserve exact block state for sync and rendering.
						gravityBlockEntity.setWeight(weight);
						gravityBlockEntity.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);

						Objects.requireNonNull(server.getWorld(worldRegistryKey)).spawnEntity(gravityBlockEntity);
						toRemove.add(pos);
					}
				} else {
					// If block changed or is no longer gravity-enabled, remove from checks
					toRemove.add(pos);
				}
			}
			blocksToCheck.removeAll(toRemove);
		});


		LOGGER.info("Aetherial Skies has been successfully initialized!");
	}


	public static void addBlockToCheck(BlockPos pos) {
		if (!blocksToCheck.contains(pos)) {
			blocksToCheck.add(pos);
		}
	}
}