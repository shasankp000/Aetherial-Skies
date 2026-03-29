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
 * Temporarily retained as a no-op stub.
 * Will be replaced entirely by the Jolt-JNI terrain block placement
 * approach in Step 5 (ShipPhysicsBlockBridge).
 *
 * The previous implementation caused phantom solid blocks due to
 * World#getBlockState being too broad a mixin target.
 * For now collision is handled by ShipDeckSnapHelper (snap/eject)
 * until the Jolt bridge is complete.
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
        // No-op until Jolt Step 5 (ShipPhysicsBlockBridge) is complete.
    }
}
