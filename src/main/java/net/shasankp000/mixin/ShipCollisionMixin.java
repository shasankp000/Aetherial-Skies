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
 * a rendered ship block, we make every ship block — deck, hull sides,
 * bottom, and helm — solid without placing real blocks in the world.
 *
 * Guard: only inject when the real world block does NOT block movement
 * (i.e. is air, water, lava, seagrass, etc.). This covers all submerged
 * hull positions and the helm regardless of water level, while never
 * overriding real solid terrain blocks.
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
        if (returned == null) return;

        // Only override non-solid blocks (air, water, lava, etc.).
        // blocksMovement() returns true for full-cube solid blocks,
        // so we skip those to never shadow real terrain.
        if (returned.blocksMovement()) return;

        if (ShipCollisionProvider.isShipBlock(pos)) {
            cir.setReturnValue(Blocks.STONE.getDefaultState());
        }
    }
}
