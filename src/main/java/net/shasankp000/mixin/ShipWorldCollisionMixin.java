package net.shasankp000.mixin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.shasankp000.Ship.Client.ShipCollisionProvider;

/**
 * CLIENT-ONLY mixin on World (not ClientWorld).
 *
 * getBlockState() is declared on World and inherited by ClientWorld —
 * Mixin cannot resolve the descriptor when targeting a subclass that
 * only inherits the method. Targeting World covers ClientWorld calls.
 *
 * A isClient() guard ensures we only act client-side so server world
 * queries are never affected.
 *
 * This intercepts the swim physics path (Entity#updateSwimming,
 * isInsideWall, LivingEntity#travel) which calls World#getBlockState
 * directly, bypassing ChunkCache entirely.
 */
@Environment(EnvType.CLIENT)
@Mixin(World.class)
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
        World self = (World)(Object) this;
        if (!self.isClient()) return;  // server worlds: never touch

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
