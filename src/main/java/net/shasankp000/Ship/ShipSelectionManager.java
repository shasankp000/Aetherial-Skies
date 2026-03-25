package net.shasankp000.Ship;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ShipSelectionManager {
    private static final Map<UUID, BlockPos> FIRST_CORNERS = new HashMap<>();
    private static final Map<UUID, BlockPos> SECOND_CORNERS = new HashMap<>();

    private ShipSelectionManager() {
    }

    public static void setFirstCorner(UUID playerId, BlockPos pos) {
        FIRST_CORNERS.put(playerId, pos.toImmutable());
    }

    public static void setSecondCorner(UUID playerId, BlockPos pos) {
        SECOND_CORNERS.put(playerId, pos.toImmutable());
    }

    public static Optional<BlockPos> getFirstCorner(UUID playerId) {
        return Optional.ofNullable(FIRST_CORNERS.get(playerId));
    }

    public static Optional<BlockPos> getSecondCorner(UUID playerId) {
        return Optional.ofNullable(SECOND_CORNERS.get(playerId));
    }

    public static void clearSecondCorner(UUID playerId) {
        SECOND_CORNERS.remove(playerId);
    }

    public static void clear(UUID playerId) {
        FIRST_CORNERS.remove(playerId);
        SECOND_CORNERS.remove(playerId);
    }

    public static Optional<Selection> getSelection(UUID playerId) {
        BlockPos first = FIRST_CORNERS.get(playerId);
        BlockPos second = SECOND_CORNERS.get(playerId);
        if (first == null || second == null) {
            return Optional.empty();
        }

        BlockPos min = new BlockPos(
                Math.min(first.getX(), second.getX()),
                Math.min(first.getY(), second.getY()),
                Math.min(first.getZ(), second.getZ())
        );
        BlockPos max = new BlockPos(
                Math.max(first.getX(), second.getX()),
                Math.max(first.getY(), second.getY()),
                Math.max(first.getZ(), second.getZ())
        );
        return Optional.of(new Selection(first, second, min, max));
    }

    public static Optional<Box> getRenderBox(UUID playerId) {
        BlockPos first = FIRST_CORNERS.get(playerId);
        BlockPos second = SECOND_CORNERS.get(playerId);
        if (first == null) {
            return Optional.empty();
        }

        if (second == null) {
            return Optional.of(new Box(first));
        }

        BlockPos min = new BlockPos(
                Math.min(first.getX(), second.getX()),
                Math.min(first.getY(), second.getY()),
                Math.min(first.getZ(), second.getZ())
        );
        BlockPos max = new BlockPos(
                Math.max(first.getX(), second.getX()),
                Math.max(first.getY(), second.getY()),
                Math.max(first.getZ(), second.getZ())
        );
        return Optional.of(new Box(min, max.add(1, 1, 1)));
    }

    public record Selection(BlockPos firstCorner, BlockPos secondCorner, BlockPos min, BlockPos max) {
    }
}
