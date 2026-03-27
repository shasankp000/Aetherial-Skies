package net.shasankp000.mixin;

import net.minecraft.entity.vehicle.BoatEntity;
import net.shasankp000.Entity.ShipBoatEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BoatEntity.class)
public class BoatEntityMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void aetherialSkies$suppressVanillaBoatTickForShip(CallbackInfo ci) {
        if ((Object) this instanceof ShipBoatEntity) {
            ci.cancel();
        }
    }
}

