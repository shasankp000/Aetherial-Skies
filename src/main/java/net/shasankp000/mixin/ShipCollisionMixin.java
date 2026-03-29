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
 * Intercepts the block-voxel-shape collision engine
 * (Entity#move / VoxelShapes#calculateMaxOffset) which queries
 * ChunkCache.getBlockState() for solid shapes.
 *
 * Guard: only override air or fluid blocks. In Yarn 1.20.1 there are no
 * separate FLOWING_WATER / FLOWING_LAVA entries — Blocks.WATER and
 * Blocks.LAVA cover both source and flowing variants via FluidState.
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
        if (!isAirOrFluid(state)) return;

        if (ShipCollisionProvider.isShipBlock(pos)) {
            cir.setReturnValue(Blocks.STONE.getDefaultState());
        }
    }

    private static boolean isAirOrFluid(BlockState state) {
        return state.isAir()
            || state.isOf(Blocks.WATER)
            || state.isOf(Blocks.LAVA);
    }
}
