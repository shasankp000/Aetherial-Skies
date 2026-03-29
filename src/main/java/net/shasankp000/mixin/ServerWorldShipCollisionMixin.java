package net.shasankp000.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.shasankp000.Ship.Physics.ShipBlockLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Server-side collision bridge: makes Minecraft's physics pipeline see
 * ship blocks as solid geometry in the overworld.
 *
 * Why World and not ServerWorld:
 *   getBlockState(BlockPos) is declared on BlockView and implemented on
 *   World. ServerWorld inherits it without overriding. Mixin cannot resolve
 *   an inherited method on a subclass — it must target the class that owns
 *   the implementation, which is World.
 *
 * The isClient() guard ensures we only intercept server-side queries.
 * ClientWorld extends World too, so without the guard we would affect the
 * client render thread as well (unwanted — the client has its own system).
 *
 * Guard conditions (all must pass):
 *   1. !self.isClient()   — server thread only
 *   2. !insideLookup      — re-entrancy guard (ThreadLocal per thread)
 *   3. existing is air or fluid — never override real solid blocks
 */
@Mixin(World.class)
public abstract class ServerWorldShipCollisionMixin {

    /** Per-thread re-entrancy guard — prevents infinite recursion if
     *  ShipBlockLookup itself ever calls World.getBlockState. */
    private static final ThreadLocal<Boolean> insideLookup =
        ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(
        method = "getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;",
        at = @At("RETURN"),
        cancellable = true
    )
    private void injectShipBlockState(
            BlockPos pos,
            CallbackInfoReturnable<BlockState> cir
    ) {
        // Server-side only — isClient() distinguishes ServerWorld from ClientWorld
        World self = (World)(Object) this;
        if (self.isClient()) return;

        // Re-entrancy guard
        if (insideLookup.get()) return;

        BlockState existing = cir.getReturnValue();
        if (existing == null) return;

        // Only override air and fluids — never replace real solid blocks
        if (!isAirOrFluid(existing)) return;

        insideLookup.set(Boolean.TRUE);
        try {
            BlockState shipState = ShipBlockLookup.getShipBlockState(pos);
            if (shipState != null) {
                cir.setReturnValue(shipState);
            }
        } finally {
            insideLookup.set(Boolean.FALSE);
        }
    }

    private static boolean isAirOrFluid(BlockState state) {
        return state.isAir()
            || state.isOf(Blocks.WATER)
            || state.isOf(Blocks.LAVA);
    }
}
