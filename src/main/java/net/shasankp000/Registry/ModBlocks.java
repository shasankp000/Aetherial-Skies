package net.shasankp000.Registry;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.shasankp000.Ship.Block.ShipHelmBlock;
import net.shasankp000.AetherialSkies;

public final class ModBlocks {
    public static final Block SHIPWRIGHTS_WORKBENCH = registerBlock(
            "shipwright_workbench",
            new Block(AbstractBlock.Settings.copy(Blocks.CRAFTING_TABLE))
    );

    public static final Block SHIP_HELM = registerBlock(
            "ship_helm",
            new ShipHelmBlock(AbstractBlock.Settings.copy(Blocks.OAK_PLANKS).strength(2.0f, 3.0f))
    );

    private ModBlocks() {
    }

    private static Block registerBlock(String name, Block block) {
        return Registry.register(Registries.BLOCK, new Identifier(AetherialSkies.MOD_ID, name), block);
    }

    public static void registerModBlocks() {
        AetherialSkies.LOGGER.info("Registering ship blocks for {}", AetherialSkies.MOD_ID);
    }
}
