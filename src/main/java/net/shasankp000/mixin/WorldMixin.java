package net.shasankp000.mixin;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shasankp000.AetherialSkies;
import net.shasankp000.Gravity.GravityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public abstract class WorldMixin {
    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
            at = @At("RETURN"))
    private void onSetBlockState(BlockPos pos, BlockState state, int flags, int maxUpdateDepth,
                                 CallbackInfoReturnable<Boolean> cir) {
        // Check if the block was successfully set and is gravity-enabled
        if (cir.getReturnValue() && GravityData.isGravityEnabled(state.getBlock())) {
            AetherialSkies.addBlockToCheck(pos); // Assume this method exists to add to blocksToCheck
        }
    }

    // Replace this with your logic to check if a block is gravity-enabled
    @Unique
    private boolean isGravityEnabled(Block block) {
        // Example: check against a list from gravity_enabled.json
        return GravityData.isGravityEnabled(block);
    }
}