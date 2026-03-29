package net.shasankp000;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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
import net.shasankp000.Ship.Command.ShipCommands;
import net.shasankp000.Ship.Physics.ShipTransformManager;
import net.shasankp000.Ship.Structure.ShipStructureManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class AetherialSkies implements ModInitializer {
    public static final String MOD_ID = "aetherial-skies";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Set<BlockPos> blocksToCheck = new HashSet<>();

    public static Set<BlockPos> getBlocksToCheck() { return blocksToCheck; }

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Aetherial Skies...");
        ModEntityTypes.register();
        ModBlocks.registerModBlocks();
        ModItems.registerModItems();

        // Register /ship command.
        CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) ->
                ShipCommands.register(dispatcher)
        );

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ShipStructureManager.getInstance().init(server);
            ShipTransformManager.getInstance().init(server);
            LOGGER.info("[AetherialSkies] ShipStructureManager + ShipTransformManager initialized.");
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ShipTransformManager.getInstance().tick();

            Set<BlockPos> toRemove = new HashSet<>();
            RegistryKey<World> worldRegistryKey =
                server.getCommandSource().getWorld().getRegistryKey();

            for (BlockPos pos : blocksToCheck) {
                BlockState state = server.getOverworld().getBlockState(pos);

                if (GravityData.isGravityEnabled(state.getBlock())) {
                    double weight = GravityData.getWeight(state.getBlock());
                    BlockPos below = pos.down();
                    if (server.getOverworld().getBlockState(below).isAir()) {
                        server.getOverworld().removeBlock(pos, false);
                        GravityBlockEntity gbe = new GravityBlockEntity(
                            ModEntityTypes.GRAVITY_BLOCK_ENTITY, server.getOverworld());
                        gbe.setBlockState(state);
                        gbe.setWeight(weight);
                        gbe.refreshPositionAndAngles(
                            pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
                        gbe.setVelocity(
                            (server.getOverworld().getRandom().nextFloat() - 0.5f) * 0.08f,
                            0.0,
                            (server.getOverworld().getRandom().nextFloat() - 0.5f) * 0.08f
                        );
                        Objects.requireNonNull(
                            server.getWorld(worldRegistryKey)).spawnEntity(gbe);
                        toRemove.add(pos);
                    }
                } else {
                    toRemove.add(pos);
                }
            }
            blocksToCheck.removeAll(toRemove);
        });

        LOGGER.info("Aetherial Skies has been successfully initialized!");
    }

    public static void addBlockToCheck(BlockPos pos) {
        if (!blocksToCheck.contains(pos)) blocksToCheck.add(pos);
    }
}
