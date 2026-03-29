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
 * Server-side position-correction mixin.
 *
 * Fires at HEAD of LivingEntity#travel (before vanilla physics runs) and
 * handles two cases via ShipDeckSnapHelper:
 *
 *  1. Player is INSIDE the hull (swam up from below) -> eject downward.
 *  2. Player is ON TOP of / just fell through deck   -> snap upward.
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity {

    @Inject(method = "travel", at = @At("HEAD"))
    private void onTravelHead(Vec3d movementInput, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!(self instanceof PlayerEntity player)) return;
        if (player.isSpectator()) return;
        if (player.getWorld().isClient()) return;

        double px = player.getX(), pz = player.getZ(), feetY = player.getY();

        // --- Case 1: player is inside the hull -> eject downward ---
        double ejectY = ShipDeckSnapHelper.findHullEjectY(px, pz, feetY);
        if (!Double.isNaN(ejectY)) {
            player.setPosition(px, ejectY, pz);
            Vec3d vel = player.getVelocity();
            // Kill upward velocity so they cannot immediately re-enter
            player.setVelocity(vel.x, Math.min(vel.y, 0.0D), vel.z);
            player.velocityModified = true;
            return; // skip top-snap this tick
        }

        // --- Case 2: player on deck or just fell through -> snap up ---
        if (player.getVelocity().y > 0.001D) return; // jumping

        double floorY = ShipDeckSnapHelper.findBestFloorY(px, pz, feetY);
        if (Double.isNaN(floorY)) return;

        if (feetY < floorY) {
            player.setPosition(px, floorY, pz);
            Vec3d vel = player.getVelocity();
            player.setVelocity(vel.x, 0.0D, vel.z);
            player.setOnGround(true);
        } else if (feetY <= floorY + ShipDeckSnapHelper.MAX_ABOVE) {
            player.setPosition(px, floorY, pz);
            Vec3d vel = player.getVelocity();
            if (vel.y < 0) player.setVelocity(vel.x, 0.0D, vel.z);
            player.setOnGround(true);
        }
    }
}
