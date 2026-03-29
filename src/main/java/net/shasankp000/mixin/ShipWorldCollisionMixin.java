package net.shasankp000.mixin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.shasankp000.Ship.Client.ShipCollisionProvider;

/**
 * CLIENT-ONLY mixin on ClientWorld.
 *
 * ChunkCache.getBlockState() is only called by the block-voxel-shape
 * collision engine (Entity#move). Swim physics uses a completely different
 * code path:
 *
 *   Entity#updateSwimming  -> World#getBlockState (to find water)
 *   Entity#isInsideWall    -> World#getBlockState (to find solid blocks)
 *   LivingEntity#travel    -> World#getFluidState (buoyancy / swim speed)
 *
 * All of these call ClientWorld#getBlockState directly, bypassing ChunkCache
 * entirely. This mixin intercepts those calls so that swim movement also
 * sees solid stone at every ship-block position, preventing the player from
 * swimming up through the hull from below or through the sides.
 *
 * Same guard as ShipCollisionMixin: only override air and fluid blocks,
 * never real solid terrain.
 */
@Environment(EnvType.CLIENT)
@Mixin(ClientWorld.class)
public abstract class ShipWorldCollisionMixin {

    @Inject(
        method = "getBlockState",
        at = @At("RETURN"),
        cancellable = true
    )
    private void injectShipBlockStateWorld(
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
        if (state.isAir()) return true;
        if (state.isOf(Blocks.WATER))         return true;
        if (state.isOf(Blocks.LAVA))          return true;
        if (state.isOf(Blocks.FLOWING_WATER)) return true;
        if (state.isOf(Blocks.FLOWING_LAVA))  return true;
        return false;
    }
}
