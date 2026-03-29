package net.shasankp000.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Util.ShipCollisionHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server-side deck-snap mixin.
 *
 * Injects at the HEAD of LivingEntity#travel so that BEFORE vanilla physics
 * runs for this tick, we snap the player flush onto any GravityBlockEntity
 * deck they are standing on or have just fallen through.
 *
 * Why HEAD and not TAIL?
 *   If we correct AFTER travel() runs the player's velocity has already been
 *   integrated and their position packet has been queued for this tick.
 *   Correcting at HEAD means vanilla never sees the "inside-block" state.
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity {

    @Inject(method = "travel", at = @At("HEAD"))
    private void onTravelHead(Vec3d movementInput, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;

        // Only apply to server-side players; spectators are skipped
        if (!(self instanceof PlayerEntity player)) return;
        if (player.isSpectator()) return;
        if (player.getWorld().isClient) return; // client handled separately in MixinClientPlayerEntity

        double floorY = ShipCollisionHelper.findFloorY(player, player.getWorld());
        if (Double.isNaN(floorY)) return;

        // Only snap when falling or stationary — don't interfere with jumps
        if (!ShipCollisionHelper.isFallingOrNeutral(player)) return;

        double feetY = player.getY();

        if (feetY < floorY) {
            // Player has already passed through the deck this tick — push back up
            player.setPosition(player.getX(), floorY, player.getZ());
            Vec3d vel = player.getVelocity();
            if (vel.y < 0) player.setVelocity(vel.x, 0.0, vel.z);
            player.setOnGround(true);
        } else if (feetY <= floorY + ShipCollisionHelper.MAX_ABOVE) {
            // Player is resting on or fractionally above the top face — snap flush
            player.setPosition(player.getX(), floorY, player.getZ());
            Vec3d vel = player.getVelocity();
            if (vel.y < 0) player.setVelocity(vel.x, 0.0, vel.z);
            player.setOnGround(true);
        }
    }
}
