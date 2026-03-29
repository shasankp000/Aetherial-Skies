package net.shasankp000.mixin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.shasankp000.Ship.Client.ShipCollisionProvider;

/**
 * CLIENT-ONLY mixin.
 *
 * Intercepts BlockView.getCollisionShape so that any BlockPos occupied by a
 * rendered ship block returns a full-cube VoxelShape.
 *
 * This makes the ship deck solid to the player’s collision engine without
 * placing any real blocks in the world.
 *
 * Target: AbstractBlockView (the base of ClientWorld, ChunkCache, etc.)
 * because player collision checks go through BlockView.getCollisionShape.
 */
@Environment(EnvType.CLIENT)
@Mixin(net.minecraft.world.AbstractWorldView.class)
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
        // Only intercept if the returned state is air (the real world has
        // nothing there) and a ship block occupies this position.
        BlockState returned = cir.getReturnValue();
        if (returned == null || !returned.isAir()) return;

        if (ShipCollisionProvider.isShipBlock(pos)) {
            // Return stone: full-cube collision shape, never null, never
            // a special shape.  The renderer still draws the real block
            // model from ShipStructureRenderer — this is invisible.
            cir.setReturnValue(Blocks.STONE.getDefaultState());
        }
    }
}
