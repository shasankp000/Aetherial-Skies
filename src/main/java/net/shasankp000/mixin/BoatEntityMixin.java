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
     * For ShipBoatEntity, cancel BoatEntity.tick() just before it applies movement,
     * NOT at HEAD.
     *
     * Cancelling at HEAD was preventing the first half of BoatEntity.tick() from
     * running — the part that sets touchingWater, location flags, and internal boat
     * state that BoatEntity.interact() reads before allowing a passenger to mount.
     *
     * By cancelling here (at the first call to updatePaddles, which is the first
     * movement-related step in BoatEntity.tick() under Yarn 1.20.1 mappings),
     * all flag setup and interaction logic runs normally, and only the vanilla
     * movement integration is suppressed. ShipBoatEntity.tick() handles movement
     * via ShipPhysicsEngine.
     *
     * Yarn 1.20.1+build.10 method name: updatePaddles()
     */
    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/vehicle/BoatEntity;updatePaddles()V"
        ),
        cancellable = true
    )
    private void aetherialSkies$suppressVanillaBoatMovementForShip(CallbackInfo ci) {
        if ((Object) this instanceof ShipBoatEntity) {
            ci.cancel();
        }
    }
}
