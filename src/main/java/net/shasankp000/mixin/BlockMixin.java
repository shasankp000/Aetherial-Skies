package net.shasankp000.mixin;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shasankp000.Gravity.GravityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.shasankp000.AetherialSkies;


@Mixin(Block.class)
public class BlockMixin {

	@Inject(method = "onPlaced", at = @At("TAIL"))
	private void onBlockPlacedInject(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack itemStack, CallbackInfo ci) {
		Identifier blockId = Registries.BLOCK.getId(state.getBlock());
		if (GravityData.isGravityEnabled(state.getBlock())) {
			if (!AetherialSkies.getBlocksToCheck().contains(pos)) {
				AetherialSkies.getBlocksToCheck().add(pos);
				System.out.println("Detected block " + state.getBlock().getName().getString() + " at " + pos);
			}
		}
	}

	private boolean canFallThrough(BlockState state) {
		return state.isAir(); // Add more conditions if needed (e.g., water, lava)
	}

}