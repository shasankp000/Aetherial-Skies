package net.shasankp000.mixin;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.shasankp000.Util.ShipCollisionHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Client-side mirror of the deck-snap logic.
 *
 * The Minecraft client runs its own physics simulation for the local player
 * (ClientPlayerEntity). Without this mixin, the following bad sequence happens:
 *
 *   1. Client simulates travel() -> falls through the deck.
 *   2. Server corrects position via MixinLivingEntity.
 *   3. Client receives the correction and rubber-bands visually.
 *
 * By applying the same snap here at the start of the client tick, the client
 * and server always agree, and there is no rubber-band at all.
 *
 * We inject into tick() rather than travel() on the client because
 * ClientPlayerEntity overrides tick() as its outer movement driver.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class MixinClientPlayerEntity {

    @Inject(method = "tick", at = @At("HEAD"))
    private void onClientTickHead(CallbackInfo ci) {
        ClientPlayerEntity self = (ClientPlayerEntity) (Object) this;
        if (self.isSpectator()) return;

        double floorY = ShipCollisionHelper.findFloorY(self, self.getWorld());
        if (Double.isNaN(floorY)) return;
        if (!ShipCollisionHelper.isFallingOrNeutral(self)) return;

        double feetY = self.getY();

        if (feetY < floorY || (feetY >= floorY && feetY <= floorY + ShipCollisionHelper.MAX_ABOVE)) {
            self.setPosition(self.getX(), floorY, self.getZ());
            Vec3d vel = self.getVelocity();
            if (vel.y < 0) self.setVelocity(vel.x, 0.0, vel.z);
            self.setOnGround(true);
        }
    }
}
