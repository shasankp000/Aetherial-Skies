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
 * Intercepts getBlockState so that any BlockPos occupied by a rendered ship
 * block returns a full-cube BlockState (stone) instead of air.
 * This makes the ship deck solid to the player's collision engine without
 * placing any real blocks in the world.
 *
 * Only fires when the real world has air at that position, so it never
 * overrides actual world blocks.
 */
@Environment(EnvType.CLIENT)
@Mixin(ClientWorld.class)
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
