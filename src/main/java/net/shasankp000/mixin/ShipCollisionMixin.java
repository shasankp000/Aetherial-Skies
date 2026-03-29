package net.shasankp000.mixin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.chunk.ChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
 * Guard: we only inject when the real world block is NOT already a full
 * solid cube. This covers air, water, lava, and any other non-solid block
 * that might occupy the same position (e.g. submerged hull blocks sit
 * inside water). We never override a real solid block.
 */
@Environment(EnvType.CLIENT)
@Mixin(ChunkCache.class)
public abstract class ShipCollisionMixin {

    @Shadow public abstract BlockView getWorld();

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

        // Skip if the real world block is already fully solid —
        // no need to override it and we must not shadow real terrain.
        if (returned.isFullCube(getWorld(), pos)) return;

        if (ShipCollisionProvider.isShipBlock(pos)) {
            cir.setReturnValue(Blocks.STONE.getDefaultState());
        }
    }
}
