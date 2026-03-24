package net.shasankp000.Util;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import java.util.HashMap;
import java.util.Map;

public class BlockStateRegistry {
    private static final Map<String, BlockState> BLOCK_STATE_MAP = new HashMap<>();

    static {
        // Populate the map with block names (in lowercase) and their default states.
        BLOCK_STATE_MAP.put("sand", Blocks.SAND.getDefaultState());
        BLOCK_STATE_MAP.put("gravel", Blocks.GRAVEL.getDefaultState());
        BLOCK_STATE_MAP.put("anvil", Blocks.ANVIL.getDefaultState());
        BLOCK_STATE_MAP.put("dirt", Blocks.DIRT.getDefaultState());
        BLOCK_STATE_MAP.put("cobblestone", Blocks.COBBLESTONE.getDefaultState());
        BLOCK_STATE_MAP.put("stone", Blocks.STONE.getDefaultState());
        BLOCK_STATE_MAP.put("grass_block", Blocks.GRASS_BLOCK.getDefaultState());
        BLOCK_STATE_MAP.put("oak_planks", Blocks.OAK_PLANKS.getDefaultState());
        BLOCK_STATE_MAP.put("bookshelf", Blocks.BOOKSHELF.getDefaultState());
        BLOCK_STATE_MAP.put("netherrack", Blocks.NETHERRACK.getDefaultState());
        BLOCK_STATE_MAP.put("end_stone", Blocks.END_STONE.getDefaultState());
        BLOCK_STATE_MAP.put("clay", Blocks.CLAY.getDefaultState());
        BLOCK_STATE_MAP.put("brick_block", Blocks.BRICKS.getDefaultState());
        BLOCK_STATE_MAP.put("quartz_block", Blocks.QUARTZ_BLOCK.getDefaultState());
    }

    /**
     * Retrieves the default BlockState for the given block name.
     * If the block name is not in the map, returns AIR as fallback.
     *
     * @param blockName the block's name (e.g. "stone", "sand")
     * @return the default BlockState for that block
     */
    public static BlockState getDefaultStateFor(String blockName) {
        if (blockName == null || blockName.isBlank()) {
            return Blocks.AIR.getDefaultState();
        }

        String normalized = blockName.toLowerCase();

        Identifier id = Identifier.tryParse(normalized.contains(":") ? normalized : "minecraft:" + normalized);
        if (id != null && Registries.BLOCK.containsId(id)) {
            return Registries.BLOCK.get(id).getDefaultState();
        }

        String path = normalized.contains(":") ? normalized.substring(normalized.indexOf(':') + 1) : normalized;
        return BLOCK_STATE_MAP.getOrDefault(path, Blocks.AIR.getDefaultState());
    }
}
