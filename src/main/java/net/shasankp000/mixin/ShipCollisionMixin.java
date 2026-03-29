package net.shasankp000.mixin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.shasankp000.Ship.Client.ShipCollisionProvider;

/**
 * CLIENT-ONLY mixin on ChunkCache.
 *
 * ChunkCache is the BlockView the block-voxel-shape collision engine
 * (Entity#move, VoxelShapes#calculateMaxOffset) queries for solid shapes.
 *
 * Guard change: we no longer use blocksMovement() because in 1.20.1
 * Blocks.WATER.getDefaultState().blocksMovement() returns TRUE (water uses
 * a separate fluid path and does not set noCollision()). That caused the
 * mixin to bail before isShipBlock() was ever reached for any water-filled
 * hull position, making every submerged hull face non-solid.
 *
 * New guard: skip only blocks that are genuinely full-cube solid terrain
 * (i.e. their collision shape fills the entire unit cube). isFullCube is
 * unavailable without a BlockView reference, so we use the combination:
 *   - not air
 *   - not a fluid (water / lava / flowing variants)
 *   - isSolidBlock would need world context; instead we check the
 *     material path: if the state IS solid AND is not a fluid, it is real
 *     terrain we must not shadow.
 *
 * Simplest correct guard: only inject when the real block at that position
 * is AIR or a FLUID. Those are the only block types that can legally
 * occupy a ship-block position in a floating ship scenario.
 */
@Environment(EnvType.CLIENT)
@Mixin(ChunkCache.class)
public abstract class ShipCollisionMixin {

    @Inject(
        method = "getBlockState",
        at = @At("RETURN"),
        cancellable = true
    )
    private void injectShipBlockState(
            BlockPos pos,
            CallbackInfoReturnable<BlockState> cir
    ) {
        BlockState state = cir.getReturnValue();
        if (state == null) return;

        // Only override positions that contain air or a fluid.
        // Real solid terrain blocks are never overridden.
        if (!isAirOrFluid(state)) return;

        if (ShipCollisionProvider.isShipBlock(pos)) {
            cir.setReturnValue(Blocks.STONE.getDefaultState());
        }
    }

    private static boolean isAirOrFluid(BlockState state) {
        if (state.isAir()) return true;
        // Water and lava (both source and flowing)
        if (state.isOf(Blocks.WATER))        return true;
        if (state.isOf(Blocks.LAVA))         return true;
        if (state.isOf(Blocks.FLOWING_WATER)) return true;
        if (state.isOf(Blocks.FLOWING_LAVA))  return true;
        return false;
    }
}
