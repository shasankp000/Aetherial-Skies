package net.shasankp000.Util;

import java.util.Set;
import java.util.HashSet;

public class HandMineableBlocks {

    private static final Set<String> HAND_MINEABLE_IDS = new HashSet<>();

    static {
        // Common Earth Blocks:
        HAND_MINEABLE_IDS.add("dirt");
        HAND_MINEABLE_IDS.add("grass_block");
        HAND_MINEABLE_IDS.add("mycelium");

        // Plants:
        HAND_MINEABLE_IDS.add("vines");
        // For cocoa, mushrooms, chorus – you might use the block names if they exist:
        HAND_MINEABLE_IDS.add("chorus_flower");
        HAND_MINEABLE_IDS.add("chorus_plant");
        // Other:
        HAND_MINEABLE_IDS.add("clay");
        HAND_MINEABLE_IDS.add("farmland");



        // (Skip food crops such as potato crop, wheat crop, etc.)

        // Snow and Ice:
        HAND_MINEABLE_IDS.add("snow");
        HAND_MINEABLE_IDS.add("ice");
        HAND_MINEABLE_IDS.add("packed_ice");
        HAND_MINEABLE_IDS.add("snow_block");

        // Liquids and soul blocks:
        // (Water and lava are generally not mineable, so we leave them out.)
        HAND_MINEABLE_IDS.add("soul_sand");
        HAND_MINEABLE_IDS.add("soul_soil");


        // Coral (if you want them as hand mineable):
        HAND_MINEABLE_IDS.add("coral_block");
        HAND_MINEABLE_IDS.add("dead_coral_block");


        HAND_MINEABLE_IDS.add("moss_block");

    }

    /**
     * Checks if the provided block identifier string (e.g. "minecraft:stone")
     * is considered hand-mineable.
     *
     * @param blockId the block identifier string.
     * @return true if the block is hand mineable, false otherwise.
     */
    public static boolean isHandMineable(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return false;
        }

        String normalized = blockId.toLowerCase();
        if (normalized.contains(":")) {
            normalized = normalized.substring(normalized.indexOf(':') + 1);
        }

        return HAND_MINEABLE_IDS.contains(normalized);
    }
}
