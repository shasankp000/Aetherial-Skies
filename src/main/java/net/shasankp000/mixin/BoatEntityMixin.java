package net.shasankp000.mixin;

import net.minecraft.entity.vehicle.BoatEntity;
import net.shasankp000.Entity.ShipBoatEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BoatEntity.class)
public class BoatEntityMixin {

    /**
     * For ShipBoatEntity, cancel BoatEntity.tick() at the point where it first
     * tries to apply movement (updateVelocity), NOT at HEAD.
     *
     * Cancelling at HEAD prevented BoatEntity.tick() from ever running its first
     * half, which sets touchingWater, location flags, and the internal boat state
     * that BoatEntity.interact() reads before allowing a passenger to mount.
     * By cancelling here instead, all flag-setup and interaction logic runs
     * normally, and only the vanilla movement integration is suppressed.
     * ShipBoatEntity.tick() handles movement via ShipPhysicsEngine.
     */
    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/vehicle/BoatEntity;updateVelocity(FLnet/minecraft/util/math/Vec3d;)V"
        ),
        cancellable = true
    )
    private void aetherialSkies$suppressVanillaBoatMovementForShip(CallbackInfo ci) {
        if ((Object) this instanceof ShipBoatEntity) {
            ci.cancel();
        }
    }
}
