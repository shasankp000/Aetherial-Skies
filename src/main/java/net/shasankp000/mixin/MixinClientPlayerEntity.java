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
 * Client-side mirror of the deck-snap logic.
 *
 * Without this, the client simulates its own fall each tick, disagrees
 * with the server's correction, and the player rubber-bands visually.
 *
 * Delegates entirely to ShipDeckSnapHelper so this mixin has no
 * non-private static methods (Mixin requirement).
 */
@Environment(EnvType.CLIENT)
@Mixin(ClientPlayerEntity.class)
public abstract class MixinClientPlayerEntity {

    @Inject(method = "tick", at = @At("HEAD"))
    private void onClientTickHead(CallbackInfo ci) {
        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        if (self.isSpectator()) return;

        double bestFloorY = ShipDeckSnapHelper.findBestFloorY(
                self.getX(), self.getZ(), self.getY());
        if (Double.isNaN(bestFloorY)) return;

        if (self.getVelocity().y > 0.001D) return; // jumping — don't snap

        double feetY = self.getY();
        if (feetY < bestFloorY
                || (feetY >= bestFloorY && feetY <= bestFloorY + ShipDeckSnapHelper.MAX_ABOVE)) {
            self.setPosition(self.getX(), bestFloorY, self.getZ());
            Vec3d vel = self.getVelocity();
            if (vel.y < 0) self.setVelocity(vel.x, 0.0D, vel.z);
            self.setOnGround(true);
        }
    }
}
