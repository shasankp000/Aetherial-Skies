package net.shasankp000;


import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shasankp000.Entity.GravityBlockEntity;
import net.shasankp000.Gravity.GravityData;
import net.shasankp000.Registry.ModBlocks;
import net.shasankp000.Registry.ModEntityTypes;
import net.shasankp000.Registry.ModItems;
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

	@Override
	public void onInitialize() {
		LOGGER.info("Initializing Aetherial Skies...");
		ModEntityTypes.register();
		ModBlocks.registerModBlocks();
		ModItems.registerModItems();

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
						GravityBlockEntity gravityBlockEntity = new GravityBlockEntity(ModEntityTypes.GRAVITY_BLOCK_ENTITY, server.getOverworld());
						gravityBlockEntity.setBlockState(state); // preserve exact block state for sync and rendering.
						gravityBlockEntity.setWeight(weight);
						gravityBlockEntity.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
						// Give a tiny horizontal nudge so freefall tumble starts immediately.
						gravityBlockEntity.setVelocity(
								(server.getOverworld().getRandom().nextFloat() - 0.5f) * 0.08f,
								0.0,
								(server.getOverworld().getRandom().nextFloat() - 0.5f) * 0.08f
						);

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
