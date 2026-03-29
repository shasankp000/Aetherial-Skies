package net.shasankp000.mixin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.Physics.ShipDeckSnapHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-side mirror of MixinLivingEntity's position-correction logic.
 *
 * Mirrors both the hull-eject and deck-snap cases so client and server
 * always agree on the player's Y, preventing visual rubber-band.
 */
@Environment(EnvType.CLIENT)
@Mixin(ClientPlayerEntity.class)
public abstract class MixinClientPlayerEntity {

    @Inject(method = "tick", at = @At("HEAD"))
    private void onClientTickHead(CallbackInfo ci) {
        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        if (self.isSpectator()) return;

        double px = self.getX(), pz = self.getZ(), feetY = self.getY();

        // --- Case 1: inside hull -> eject downward ---
        double ejectY = ShipDeckSnapHelper.findHullEjectY(px, pz, feetY);
        if (!Double.isNaN(ejectY)) {
            self.setPosition(px, ejectY, pz);
            Vec3d vel = self.getVelocity();
            self.setVelocity(vel.x, Math.min(vel.y, 0.0D), vel.z);
            return;
        }

        // --- Case 2: on deck or just fell through -> snap up ---
        if (self.getVelocity().y > 0.001D) return; // jumping

        double floorY = ShipDeckSnapHelper.findBestFloorY(px, pz, feetY);
        if (Double.isNaN(floorY)) return;

        if (feetY < floorY
                || (feetY >= floorY && feetY <= floorY + ShipDeckSnapHelper.MAX_ABOVE)) {
            self.setPosition(px, floorY, pz);
            Vec3d vel = self.getVelocity();
            if (vel.y < 0) self.setVelocity(vel.x, 0.0D, vel.z);
            self.setOnGround(true);
        }
    }
}
