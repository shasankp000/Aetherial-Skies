package net.shasankp000.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.Physics.ShipDeckSnapHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server-side deck-snap mixin.
 *
 * Injects at HEAD of LivingEntity#travel so that BEFORE vanilla physics
 * runs for this tick, we snap the player flush onto any ship deck they
 * are standing on or have just fallen through.
 *
 * All footprint/floor-Y logic lives in ShipDeckSnapHelper so that this
 * mixin's static methods can remain private (Mixin requirement).
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity {

    @Inject(method = "travel", at = @At("HEAD"))
    private void onTravelHead(Vec3d movementInput, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;

        if (!(self instanceof PlayerEntity player)) return;
        if (player.isSpectator()) return;
        if (player.getWorld().isClient()) return;

        double bestFloorY = ShipDeckSnapHelper.findBestFloorY(
                player.getX(), player.getZ(), player.getY());
        if (Double.isNaN(bestFloorY)) return;

        if (player.getVelocity().y > 0.001D) return; // jumping — don't snap

        double feetY = player.getY();
        if (feetY < bestFloorY) {
            // Fell through: push back up
            player.setPosition(player.getX(), bestFloorY, player.getZ());
            Vec3d vel = player.getVelocity();
            player.setVelocity(vel.x, 0.0D, vel.z);
            player.setOnGround(true);
        } else if (feetY <= bestFloorY + ShipDeckSnapHelper.MAX_ABOVE) {
            // Resting on or fractionally above deck: snap flush
            player.setPosition(player.getX(), bestFloorY, player.getZ());
            Vec3d vel = player.getVelocity();
            if (vel.y < 0) player.setVelocity(vel.x, 0.0D, vel.z);
            player.setOnGround(true);
        }
    }
}
