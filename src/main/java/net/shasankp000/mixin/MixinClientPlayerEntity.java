package net.shasankp000.mixin;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Ship.Client.ShipTransformCache;
import net.shasankp000.Ship.ShipCrateService;
import net.shasankp000.Ship.Transform.ShipTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-side mirror of the deck-snap logic.
 *
 * Without this, the following bad sequence happens every tick:
 *   1. Client travel() runs -> player falls through the deck visually.
 *   2. Server corrects Y via MixinLivingEntity / ShipPassengerTracker.
 *   3. Client receives the correction and rubber-bands backward.
 *
 * By running the same snap here at HEAD of tick(), the client and server
 * always agree on the player's Y and there is no visual rubber-band.
 *
 * Delegates to MixinLivingEntity's static helpers so the footprint logic
 * is defined exactly once.
 */
@Environment(EnvType.CLIENT)
@Mixin(ClientPlayerEntity.class)
public abstract class MixinClientPlayerEntity {

    private static final double SNAP_TOLERANCE = 1.1D;
    private static final double MAX_ABOVE      = 0.55D;

    @Inject(method = "tick", at = @At("HEAD"))
    private void onClientTickHead(CallbackInfo ci) {
        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        if (self.isSpectator()) return;

        // Reuse the exact same logic as the server-side mixin
        double bestFloorY = MixinLivingEntity.findBestFloorY(
                self.getX(), self.getZ(), self.getY());
        if (Double.isNaN(bestFloorY)) return;

        boolean falling = self.getVelocity().y <= 0.001D;
        if (!falling) return;

        double feetY = self.getY();
        if (feetY < bestFloorY || (feetY >= bestFloorY && feetY <= bestFloorY + MAX_ABOVE)) {
            self.setPosition(self.getX(), bestFloorY, self.getZ());
            Vec3d vel = self.getVelocity();
            if (vel.y < 0) self.setVelocity(vel.x, 0.0D, vel.z);
            self.setOnGround(true);
        }
    }
}
