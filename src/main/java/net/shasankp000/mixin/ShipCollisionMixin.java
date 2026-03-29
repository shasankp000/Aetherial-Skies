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
 * ChunkCache is the concrete BlockView implementation Minecraft's entity
 * collision engine queries when computing player physics (Entity.move,
 * VoxelShapes.calculateMaxOffset, etc.).
 *
 * By returning a full-cube BlockState (stone) for any position occupied by
 * a rendered ship block, we make the ship deck solid without placing real
 * blocks in the world.
 *
 * Only intercepts when the real world has air at that pos so we never
 * shadow actual world blocks.
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
        BlockState returned = cir.getReturnValue();
        if (returned == null || !returned.isAir()) return;

        if (ShipCollisionProvider.isShipBlock(pos)) {
            cir.setReturnValue(Blocks.STONE.getDefaultState());
        }
    }
}
