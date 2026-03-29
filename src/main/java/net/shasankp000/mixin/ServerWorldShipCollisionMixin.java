package net.shasankp000.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.shasankp000.Ship.Physics.ShipBlockLookup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Server-side collision bridge: makes Minecraft's physics pipeline see
 * ship blocks as solid geometry in the overworld.
 *
 * Why ServerWorld and not World:
 *   - World.getBlockState is abstract; Mixin needs a concrete target.
 *   - ServerWorld is the concrete implementation used on the server thread,
 *     which is where player movement, swim physics, and fall detection run.
 *   - No @Environment annotation — must be active server-side.
 *
 * Guard conditions (all must pass before we do anything):
 *   1. !insideLookup  — re-entrancy guard. ShipBlockLookup must never call
 *      back into World.getBlockState or we get a stack overflow. The guard
 *      is a ThreadLocal<Boolean> so each server thread has its own flag.
 *   2. The world's own result is air or a fluid — we never override a real
 *      solid block.
 *
 * When all guards pass we call ShipBlockLookup.getShipBlockState(pos). If
 * a ship block occupies that position we return its real BlockState so
 * Minecraft collision shapes, sounds, and step-up logic all work correctly.
 */
@Mixin(ServerWorld.class)
public abstract class ServerWorldShipCollisionMixin {

    /** Per-thread re-entrancy flag. */
    private static final ThreadLocal<Boolean> insideLookup =
        ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(
        method = "getBlockState",
        at = @At("RETURN"),
        cancellable = true
    )
    private void injectShipBlockState(
            BlockPos pos,
            CallbackInfoReturnable<BlockState> cir
    ) {
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
